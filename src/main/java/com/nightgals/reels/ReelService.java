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
 * Short promotional clips on the public site, posted by creators, gone after a
 * day.
 *
 * <p>A reel belongs to the creator who posted it and is an advert for her
 * profile — tapping one on the landing page opens it. Staff used to post these
 * and it was the wrong shape: a promo slot nobody could use except an
 * administrator is a channel, not a shop window.
 *
 * <p>Free to <em>watch</em> — a reel never sits behind a paywall, because its
 * whole job is to pull a stranger towards a profile where the paid things are.
 *
 * <p>Posting one needs an active package, like every other kind of upload. A
 * reel is prime placement on the landing page, and a creator who has not paid to
 * be on the platform should not be advertising on the front of it.
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
    private final com.nightgals.billing.CreatorPackageService creatorPackageService;

    /** How long a reel stays up. A day, unless a deployment says otherwise. */
    @Value("${nightgals.reels.lifetime:PT24H}")
    private Duration lifetime;


    @Transactional
    public ReelResponse post(User creator, MultipartFile file, String caption) {
        if (!creator.isApproved()) {
            throw ApiException.forbidden("verification_required",
                    "Verify your identity before posting a reel");
        }
        // Same bar as photos and video: 402 with the packages named, so the
        // client can send her straight to the one screen that unblocks it.
        creatorPackageService.requireActivePackage(creator);
        uploadValidator.validateVideo(file);

        // The cap comes from the package now, not from one number shared by
        // everybody: Black Diamond 3, Diamond 2, Pro 1. A flat limit made the
        // strip the one place a Pro creator got exactly as much of the landing
        // page as somebody paying three times as much for visibility, which is
        // the thing the tiers are supposed to be selling.
        int allowed = creatorPackageService.reelAllowanceFor(creator);
        long liveNow = reelRepository.countByPostedByIdAndExpiresAtAfter(creator.getId(), Instant.now());
        if (liveNow >= allowed) {
            throw ApiException.conflict("reel_limit_reached",
                    "Your package covers " + allowed + (allowed == 1 ? " reel" : " reels")
                    + " at a time and you have " + liveNow + " up. Each one clears itself "
                    + "within a day — take one down to post another, or move up a package.");
        }

        StoredFile stored = storageService.store(file, "reels");
        Instant now = Instant.now();

        Reel reel = reelRepository.save(Reel.builder()
                .postedBy(creator)
                .storageKey(stored.storageKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .caption(caption == null || caption.isBlank() ? null : caption.trim())
                .expiresAt(now.plus(lifetime))
                .build());

        log.info("Reel {} posted by {}, expires {}", reel.getId(), creator.getId(), reel.getExpiresAt());
        return ReelResponse.of(reel);
    }

    /** What the public site shows. Anonymous callers included. */
    @Transactional(readOnly = true)
    public List<ReelResponse> live() {
        return reelRepository.findLive(Instant.now(), creatorPackageService.visibilityEnforced())
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
        // Same reasoning as the expiry above, for a different clock. The strip
        // drops a creator who is neither on a trial nor paying, and a reel is
        // free to watch - so without this the file stays fetchable to anybody
        // holding the link, and being off the site would mean off the listing
        // only. Burned accounts fail the same test.
        User poster = reel.getPostedBy();
        if (poster.getStatus() != com.nightgals.user.UserStatus.ACTIVE
                || !creatorPackageService.isPubliclyVisible(poster)) {
            throw ApiException.notFound("Reel");
        }
        return new ReelDownload(storageService.load(reel.getStorageKey()), reel.getContentType(),
                reel.getPostedBy().getId());
    }

    /** This creator's own reels, expired ones included until the sweep clears them. */
    @Transactional(readOnly = true)
    public List<ReelResponse> mine(UUID creatorId) {
        return reelRepository.findByPostedByIdOrderByCreatedAtDesc(creatorId)
                .stream()
                .map(ReelResponse::of)
                .toList();
    }

    /**
     * Taken down early.
     *
     * <p>{@code requester} null means staff moderation, which may remove
     * anything. Otherwise it is the creator removing her own, and a reel that is
     * not hers reports as not found rather than forbidden — whether somebody
     * else's reel id exists is not her business.
     */
    @Transactional
    public void remove(UUID reelId, User requester) {
        Reel reel = reelRepository.findById(reelId)
                .orElseThrow(() -> ApiException.notFound("Reel"));
        if (requester != null && !reel.getPostedBy().getId().equals(requester.getId())) {
            throw ApiException.notFound("Reel");
        }
        storageService.delete(reel.getStorageKey());
        reelRepository.delete(reel);
        log.info("Reel {} removed by {}", reelId, requester == null ? "staff" : requester.getId());
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

    /** {@code ownerId} so the caller can tell a creator's own view from an audience's. */
    public record ReelDownload(Resource resource, String contentType, java.util.UUID ownerId) {
    }
}
