package com.nightgals.earnings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Moves earnings from PENDING to AVAILABLE once the hold period has elapsed. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EarningsReleaseJob {

    private final EarningRepository earningRepository;

    @Scheduled(cron = "${nightgals.earnings.release-cron:0 0 * * * *}")
    @Transactional
    public void releaseHeldEarnings() {
        List<Earning> due = earningRepository.findReleasable(Instant.now());
        if (due.isEmpty()) {
            return;
        }
        due.forEach(e -> e.setStatus(EarningStatus.AVAILABLE));
        log.info("Released {} earning entries from hold", due.size());
    }
}
