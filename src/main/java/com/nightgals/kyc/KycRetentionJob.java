package com.nightgals.kyc;

import com.nightgals.config.StorageProperties;
import com.nightgals.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Deletes identity document files once the retention window after a decision has
 * passed.
 *
 * <p>The database rows survive with {@code purgedAt} set: we keep the evidence
 * that a person was verified, and stop holding the scan of their passport
 * indefinitely.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KycRetentionJob {

    private final KycDocumentRepository documentRepository;
    private final StorageService storageService;
    private final StorageProperties storageProperties;

    @Scheduled(cron = "${nightgals.storage.purge-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpiredDocuments() {
        Instant cutoff = Instant.now().minus(storageProperties.kycRetention());
        List<KycDocument> expired = documentRepository.findPurgeable(cutoff);

        if (expired.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        expired.forEach(document -> {
            storageService.delete(document.getStorageKey());
            document.setPurgedAt(now);
        });

        log.info("Purged {} KYC document files older than {}", expired.size(), cutoff);
    }
}
