package com.nightgals.live;

import com.nightgals.config.LiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Ends broadcasts that have run past the limit.
 *
 * <p>Creators start a broadcast and walk away, and nothing else ever ends it:
 * the room stays open until somebody presses stop, and the provider bills every
 * minute of that whether anybody is watching or the host is still in front of
 * her camera. One forgotten tab could spend a day's shared minutes.
 *
 * <p>Each session is ended in its own transaction, through the service, so one
 * that fails to close - the provider briefly unreachable, a row changed under us
 * - is logged and retried on the next sweep rather than rolling back every other
 * session found in the same pass.
 *
 * <p>Not {@code @Transactional} itself for exactly that reason.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveOverrunJob {

    private final LiveSessionRepository sessionRepository;
    private final LiveSessionService sessionService;
    private final LiveProperties properties;

    @Scheduled(cron = "${nightgals.live.overrun-cron:0 * * * * *}")
    public void endOverrunning() {
        Duration limit = properties.maxSessionLength();
        Instant cutoff = Instant.now().minus(limit);

        for (LiveSession session : sessionRepository.findOverrunning(cutoff)) {
            try {
                sessionService.endForOverrun(session.getId(), limit);
            } catch (RuntimeException e) {
                log.warn("Could not end overrunning live session {}; will retry next sweep: {}",
                        session.getId(), e.toString());
            }
        }
    }
}
