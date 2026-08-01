package com.nightgals.live;

import com.nightgals.billing.CreatorPackageService;
import com.nightgals.common.ApiException;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The daily live allowance: 15 minutes on Pro, 45 on Diamond, 2 hours on Black
 * Diamond.
 *
 * <p>Per day and not per session, so a creator can run one long stream or six
 * short ones. What is metered is the total.
 *
 * <p>Consumption is recorded when a session <em>ends</em>, because that is when
 * its length is known. The check before starting one therefore looks at what has
 * already been used plus what is currently on air - otherwise a creator with one
 * minute left could open an unlimited broadcast and never be stopped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveQuotaService {

    private final LiveUsageRepository usageRepository;
    private final LiveSessionRepository sessionRepository;
    private final CreatorPackageService packageService;

    /** What a creator has left today, in minutes. */
    @Transactional(readOnly = true)
    public Remaining remainingToday(User creator) {
        int allowance = packageService.dailyLiveMinutesFor(creator);
        if (allowance <= 0) {
            return new Remaining(0, 0, 0);
        }
        int used = usedToday(creator.getId()) + minutesOnAir(creator.getId());
        return new Remaining(allowance, used, Math.max(0, allowance - used));
    }

    public record Remaining(int allowanceMinutes, int usedMinutes, int remainingMinutes) {

        public boolean exhausted() {
            return remainingMinutes <= 0;
        }
    }

    /**
     * The gate on going live.
     *
     * <p>Refuses with 402 when the package is the problem and 409 when the
     * allowance is - one needs an upgrade, the other needs tomorrow.
     */
    @Transactional(readOnly = true)
    public void requireCanBroadcast(User creator) {
        Remaining remaining = remainingToday(creator);

        if (remaining.allowanceMinutes() <= 0) {
            throw ApiException.paymentRequired(
                    "Going live needs a package. Pro includes 15 minutes a day, "
                    + "Diamond 45, and Black Diamond two hours.");
        }
        if (remaining.exhausted()) {
            throw ApiException.conflict("live_quota_exhausted",
                    "You have used today's " + remaining.allowanceMinutes()
                    + " live minutes. The allowance resets at midnight UTC.");
        }
    }

    /**
     * Checks a scheduled broadcast against the allowance for the day it falls on.
     *
     * <p>Only its own length is compared, not the day's usage: the day is in the
     * future, so nothing is known about what else will run then. It catches the
     * case that is knowable now - a two-hour stream booked on a package that
     * allows fifteen minutes.
     */
    @Transactional(readOnly = true)
    public void requireDurationFits(User creator, Integer durationMinutes) {
        if (durationMinutes == null) {
            return;
        }
        int allowance = packageService.dailyLiveMinutesFor(creator);
        if (allowance > 0 && durationMinutes > allowance) {
            throw ApiException.conflict("duration_exceeds_allowance",
                    "That is longer than your daily allowance of " + allowance
                    + " minutes. Shorten it, or upgrade your package.");
        }
    }

    /**
     * Books a finished session's minutes against the day it started.
     *
     * <p>Against the start date, not the end date: a stream that runs through
     * midnight belongs to the day it began, and splitting it across two would be
     * a lot of machinery for an edge case nobody would thank us for.
     */
    @Transactional
    public void record(LiveSession session) {
        int minutes = session.actualMinutes();
        if (minutes <= 0) {
            return;
        }
        UUID creatorId = session.getHost().getId();
        LocalDate day = dayOf(session.getStartedAt());

        LiveUsageDaily usage = usageRepository.findByCreatorIdAndUsageDate(creatorId, day)
                .orElseGet(() -> LiveUsageDaily.builder()
                        .creator(session.getHost())
                        .usageDate(day)
                        .minutesUsed(0)
                        .build());

        usage.setMinutesUsed(usage.getMinutesUsed() + minutes);
        usageRepository.save(usage);

        log.info("Creator {} used {} live minutes on {} ({} total)",
                creatorId, minutes, day, usage.getMinutesUsed());
    }

    // ---------------------------------------------------------------- internals

    private int usedToday(UUID creatorId) {
        return usageRepository.findByCreatorIdAndUsageDate(creatorId, today())
                .map(LiveUsageDaily::getMinutesUsed)
                .orElse(0);
    }

    /** Minutes already burned by a broadcast still running. */
    private int minutesOnAir(UUID creatorId) {
        return sessionRepository.findFirstByHostIdAndStatus(creatorId, LiveStatus.LIVE)
                .filter(s -> s.getStartedAt() != null)
                .map(s -> (int) java.time.Duration.between(s.getStartedAt(), Instant.now()).toMinutes())
                .orElse(0);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static LocalDate dayOf(Instant instant) {
        return instant == null ? today() : instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
