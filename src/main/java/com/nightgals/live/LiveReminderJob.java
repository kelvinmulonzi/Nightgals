package com.nightgals.live;

import com.nightgals.config.LiveProperties;
import com.nightgals.mail.EmailService;
import com.nightgals.social.Follow;
import com.nightgals.social.FollowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Tells followers a broadcast is about to start.
 *
 * <p>The last piece of "scheduled live events will appear on their profile so
 * followers can receive reminders" - the profile part is a listing, this is the
 * reminder.
 *
 * <p>Marks each session as reminded inside the same transaction as the send, so
 * a restart mid-sweep cannot mail the same people twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveReminderJob {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM 'at' HH:mm 'UTC'", Locale.UK).withZone(ZoneOffset.UTC);

    private final LiveSessionRepository sessionRepository;
    private final FollowService followService;
    private final EmailService emailService;
    private final LiveProperties properties;

    @Scheduled(cron = "${nightgals.live.reminder-cron:0 */5 * * * *}")
    @Transactional
    public void remind() {
        Instant now = Instant.now();
        // Only sessions inside the lead-time window. Without the lower bound a
        // session whose start has already passed - one nobody ever started -
        // would be mailed about forever.
        List<LiveSession> due = sessionRepository.findNeedingReminder(
                now, now.plus(properties.reminderLeadTime()));

        for (LiveSession session : due) {
            List<Follow> followers = followService.remindableFollowersOf(session.getHost().getId());
            for (Follow follow : followers) {
                emailService.sendLiveReminder(
                        follow.getFollower().getEmail(),
                        follow.getFollower().getUsername(),
                        session.getHost().getUsername(),
                        session.getTitle(),
                        WHEN.format(session.getScheduledFor()));
            }
            session.setReminderSentAt(now);

            if (!followers.isEmpty()) {
                log.info("Reminded {} followers about session {}", followers.size(), session.getId());
            }
        }
    }
}
