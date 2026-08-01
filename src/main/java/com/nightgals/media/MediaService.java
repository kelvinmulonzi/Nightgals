package com.nightgals.media;

import com.nightgals.billing.CreatorPackageService;
import com.nightgals.billing.EntitlementService;
import com.nightgals.billing.ItemPricingService;
import com.nightgals.common.ApiException;
import com.nightgals.common.PageResponse;
import com.nightgals.media.dto.MediaResponse;
import com.nightgals.media.dto.MediaUpdateRequest;
import com.nightgals.storage.StorageService;
import com.nightgals.storage.StoredFile;
import com.nightgals.storage.UploadValidator;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Photos and video on a member's profile.
 *
 * <p>Publishing passes two gates, in this order. {@link #requireApproved} asks who
 * you are - nobody uploads anything until a human has matched their face to a
 * government ID. {@link CreatorPackageService#requireCanPublish} then asks what
 * you paid for: bronze covers photos, silver covers video, gold covers both, and
 * each carries its own allowance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private final MediaRepository mediaRepository;
    private final StorageService storageService;
    private final UploadValidator uploadValidator;
    private final EntitlementService entitlementService;
    private final CreatorPackageService creatorPackageService;
    private final ItemPricingService pricing;

    @Transactional
    public MediaResponse upload(User user, MediaType type, MultipartFile file,
                                String caption, ContentTier tier, Long priceMinor) {
        requireApproved(user);
        ContentTier requestedTier = tier == null ? ContentTier.EXCLUSIVE : tier;
        creatorPackageService.requireCanPublish(user, type, requestedTier);

        if (type == MediaType.PHOTO) {
            uploadValidator.validateImage(file);
        } else {
            uploadValidator.validateVideo(file);
        }

        StoredFile stored = storageService.store(file, "media/" + user.getId());
        boolean firstPhoto = type == MediaType.PHOTO
                && mediaRepository.countByUserIdAndType(user.getId(), MediaType.PHOTO) == 0;

        // The first photo becomes the profile picture, and the profile picture is
        // always free - otherwise a creator's card would have no image and nobody
        // would have anything to judge them on.
        ContentTier resolvedTier = firstPhoto ? ContentTier.FREE : requestedTier;

        MediaAsset asset = MediaAsset.builder()
                .user(user)
                .type(type)
                .storageKey(stored.storageKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .checksumSha256(stored.checksumSha256())
                .caption(caption)
                .tier(resolvedTier)
                // Priced per item, by its creator. Null until she says otherwise,
                // which means the platform default.
                .unlockPriceMinor(resolvedTier == ContentTier.FREE ? null : pricing.validate(priceMinor))
                .position((int) mediaRepository.countByUserIdAndType(user.getId(), type))
                .primary(firstPhoto)
                // Published straight away: passing KYC is what earns the right to
                // post, so there is no second gate.
                .status(MediaStatus.APPROVED)
                .build();

        log.info("Media {} ({}) published by verified creator {}", type, resolvedTier, user.getId());
        return MediaResponse.of(mediaRepository.save(asset));
    }

    @Transactional(readOnly = true)
    public List<MediaResponse> listOwn(UUID userId) {
        return mediaRepository.findByUserIdOrderByPositionAscCreatedAtAsc(userId).stream()
                .map(MediaResponse::of)
                .toList();
    }

    /**
     * Another member's gallery: moderator-approved items only, and only as far as
     * the viewer has paid.
     *
     * <p>Items the creator marked {@code FREE} come back in full; {@code EXCLUSIVE}
     * ones are returned locked - present in the list, with no URL - so a client can
     * render blurred placeholders and an honest count of what unlocking reveals.
     *
     * <p>Public: {@code viewer} is null for anonymous callers, who are never
     * entitled and therefore always see the preview-only view.
     */
    @Transactional(readOnly = true)
    public List<MediaResponse> listPublic(UUID targetUserId, User viewer) {
        List<MediaAsset> approved = mediaRepository
                .findByUserIdAndStatusOrderByPositionAscCreatedAtAsc(targetUserId, MediaStatus.APPROVED);

        // One query for the whole gallery rather than one per tile.
        var viewable = entitlementService.viewableAmong(viewer, approved);

        return approved.stream()
                .map(asset -> viewable.contains(asset.getId())
                        ? MediaResponse.of(asset)
                        // Locked tiles still carry their price: that is what turns a
                        // blurred placeholder into something somebody buys.
                        : MediaResponse.locked(asset, pricing.priceOf(asset),
                                pricing.display(pricing.priceOf(asset)), pricing.currency()))
                .toList();
    }

    @Transactional
    public MediaResponse update(User user, UUID mediaId, MediaUpdateRequest request) {
        MediaAsset asset = requireOwned(user, mediaId);

        if (request.caption() != null) {
            asset.setCaption(request.caption());
        }
        if (request.position() != null) {
            asset.setPosition(request.position());
        }
        if (request.unlockPriceMinor() != null) {
            asset.setUnlockPriceMinor(pricing.validate(request.unlockPriceMinor()));
        }
        if (request.tier() != null) {
            if (asset.isPrimary() && request.tier() == ContentTier.EXCLUSIVE) {
                throw ApiException.badRequest("primary_must_be_free",
                        "Your profile picture has to stay free. Make another photo primary first.");
            }
            asset.setTier(request.tier());
        }
        if (Boolean.TRUE.equals(request.primary())) {
            if (asset.getType() != MediaType.PHOTO) {
                throw ApiException.badRequest("not_a_photo", "Only a photo can be the profile picture");
            }
            mediaRepository.clearPrimary(user.getId());
            mediaRepository.flush();
            asset.setPrimary(true);
            // Becoming the profile picture makes it free by definition.
            asset.setTier(ContentTier.FREE);
        }

        return MediaResponse.of(asset);
    }

    @Transactional
    public void delete(User user, UUID mediaId) {
        MediaAsset asset = requireOwned(user, mediaId);
        storageService.delete(asset.getStorageKey());
        mediaRepository.delete(asset);
        log.info("Media {} deleted by {}", mediaId, user.getId());
    }

    /**
     * Streams a media file.
     *
     * <p>Owners and staff see anything. Everyone else - including anonymous
     * callers, since free URLs are public - gets published items only, and only
     * {@code FREE} ones unless they have paid.
     *
     * @param viewer the caller, or null when anonymous
     */
    @Transactional(readOnly = true)
    public MediaDownload download(UUID mediaId, User viewer) {
        MediaAsset asset = mediaRepository.findById(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media"));

        boolean owner = viewer != null && asset.getUser().getId().equals(viewer.getId());
        boolean staff = viewer != null && viewer.isStaff();

        if (!owner && !staff && !asset.isVisibleToOthers()) {
            throw ApiException.notFound("Media");
        }

        if (!owner && !staff && !entitlementService.canView(viewer, asset)) {
            // Anonymous callers get 401 rather than 402: signing in is the next
            // step for them, not paying.
            throw viewer == null
                    ? ApiException.unauthorized("Sign in to see this")
                    : ApiException.paymentRequired("Unlock this item to watch it");
        }

        return new MediaDownload(storageService.load(asset.getStorageKey()), asset.getContentType());
    }

    // ------------------------------------------------------------ moderation

    /**
     * Recently posted media, newest first.
     *
     * <p>Nothing is waiting on staff - this is for spot-checking what creators
     * have published, not a queue that must be worked.
     */
    @Transactional(readOnly = true)
    public PageResponse<MediaResponse> recentMedia(Pageable pageable) {
        return PageResponse.from(mediaRepository.findRecent(pageable), MediaResponse::of);
    }

    @Transactional(readOnly = true)
    public long takenDownCount() {
        return mediaRepository.countByStatus(MediaStatus.REJECTED);
    }

    /**
     * Removes a published item. The reason is shown to the creator on their own
     * media listing, so they know what happened and why.
     */
    @Transactional
    public MediaResponse takeDown(UUID mediaId, String reason) {
        MediaAsset asset = mediaRepository.findById(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media"));
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason_required",
                    "Say why this is being removed - the creator is shown the reason");
        }

        asset.setStatus(MediaStatus.REJECTED);
        asset.setRejectionReason(reason.trim());

        log.info("Media {} taken down: {}", mediaId, reason);
        return MediaResponse.of(asset);
    }

    /** Puts a taken-down item back. */
    @Transactional
    public MediaResponse restore(UUID mediaId) {
        MediaAsset asset = mediaRepository.findById(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media"));

        asset.setStatus(MediaStatus.APPROVED);
        asset.setRejectionReason(null);

        log.info("Media {} restored", mediaId);
        return MediaResponse.of(asset);
    }

    // ------------------------------------------------------------ internals

    /** The verification gate. */
    private void requireApproved(User user) {
        if (!user.isApproved()) {
            throw ApiException.forbidden("verification_required", switch (user.getVerificationStatus()) {
                case UNVERIFIED -> "Verify your identity before posting. Start at POST /api/v1/me/kyc.";
                case PENDING_REVIEW -> "Your identity documents are still being reviewed.";
                case REJECTED -> "Your verification was not successful. Submit new documents to try again.";
                case APPROVED -> "";
            });
        }
    }

    private MediaAsset requireOwned(User user, UUID mediaId) {
        MediaAsset asset = mediaRepository.findById(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media"));
        if (!asset.getUser().getId().equals(user.getId())) {
            // 404 rather than 403: a stranger should not learn the id exists.
            throw ApiException.notFound("Media");
        }
        return asset;
    }

    public record MediaDownload(Resource resource, String contentType) {
    }
}
