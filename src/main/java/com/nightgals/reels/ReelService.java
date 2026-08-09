package com.nightgals.reels;

import com.nightgals.common.ApiException;
import com.nightgals.reels.dto.ReelResponse;
import com.nightgals.storage.StorageService;
import com.nightgals.storage.StoredFile;
import com.nightgals.storage.UploadValidator;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Short promotional clips on the public site, posted by staff, gone after a day.
 *
 * <p>Two things enforce the deadline, and they do different jobs. Reads filter
 * on {@code expires_at}, which makes the cut-off exact — a reel disappears the
 * second it is due, not whenever a job next runs. The sweep below then deletes
 * the rows and their files, which is what reclaims storage. Doing only the sweep
 * would leave expired reels on show between runs; doing only the filter would
 * keep every clip ever posted on disk forever.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReelService {

    private final ReelRepository reelRepository;
    private final StorageService storageService;
    private final UploadValidator uploadValidator;

    /** How long a reel stays up. A day, unless a deployment says otherwise. */
    @Value("${nightgals.reels.lifetime:PT24H}")
    private Duration lifetime;

    @Transactional
    public ReelResponse post(User staff, MultipartFile file, String caption) {
        uploadValidator.validateVideo(file);

        StoredFile stored = storageService.store(file, "reels");
        Instant now = Instant.now();

        Reel reel = reelRepository.save(Reel.builder()
                .postedBy(staff)
                .storageKey(stored.storageKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .caption(caption == null || caption.isBlank() ? null : caption.trim())
                .expiresAt(now.plus(lifetime))
                .build());

        log.info("Reel {} posted by {}, expires {}", reel.getId(), staff.getEmail(), reel.getExpiresAt());
        return ReelResponse.of(reel);
    }

    /** What the public site shows. Anonymous callers included. */
    @Transactional(readOnly = true)
    public List<ReelResponse> live() {
        return reelRepository.findByExpiresAtAfterOrderByCreatedAtDesc(Instant.now())
                .stream()
                .map(ReelResponse::of)
                .toList();
    }

    /** Staff view: everything, expired ones included, so a purge can be seen working. */
    @Transactional(readOnly = true)
    public List<ReelResponse> all() {
        return reelRepository.findAll()
                .stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(ReelResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReelDownload download(UUID reelId) {
        Reel reel = reelRepository.findById(reelId)
                .orElseThrow(() -> ApiException.notFound("Reel"));
        // An expired reel is gone as far as anyone asking is concerned, even in
        // the window before the sweep removes it. Otherwise a saved direct link
        // would outlive the reel it points at.
        if (!reel.isLive()) {
            throw ApiException.notFound("Reel");
        }
        return new ReelDownload(storageService.load(reel.getStorageKey()), reel.getContentType());
    }

    /** Taken down early by staff. */
    @Transactional
    public void remove(UUID reelId) {
        Reel reel = reelRepository.findById(reelId)
                .orElseThrow(() -> ApiException.notFound("Reel"));
        storageService.delete(reel.getStorageKey());
        reelRepository.delete(reel);
        log.info("Reel {} removed by staff", reelId);
    }

    /**
     * Deletes what has expired, rows and files together.
     *
     * <p>Hourly rather than by the minute: the filter on read already hides them
     * on time, so this only has to keep storage from growing, and a sweep that
     * runs constantly costs more than the disk it saves.
     */
    @Scheduled(cron = "${nightgals.reels.purge-cron:0 5 * * * *}")
    @Transactional
    public void purgeExpired() {
        List<Reel> expired = reelRepository.findByExpiresAtBefore(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        for (Reel reel : expired) {
            // Storage first: a row deleted while its file survives is a leak
            // nobody will ever find again, having lost the key that named it.
            // The reverse just means one retry.
            storageService.delete(reel.getStorageKey());
        }
        reelRepository.deleteAll(expired);
        log.info("Purged {} expired reel(s)", expired.size());
    }

    public record ReelDownload(Resource resource, String contentType) {
    }
}
