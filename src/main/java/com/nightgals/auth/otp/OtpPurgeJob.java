package com.nightgals.auth.otp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clears out dead challenges.
 *
 * <p>They are useless the moment they expire - the code hash cannot be reversed
 * and the row grants nothing - so keeping them only grows a table that every
 * sign-in writes to.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpPurgeJob {

    private final OtpService otpService;

    @Scheduled(cron = "${nightgals.otp.purge-cron:0 15 4 * * *}")
    public void purge() {
        int removed = otpService.purgeExpired();
        if (removed > 0) {
            log.info("Purged {} expired one-time codes", removed);
        }
    }
}
