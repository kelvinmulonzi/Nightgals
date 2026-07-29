package com.nightgals.discovery;

import com.nightgals.billing.EntitlementService;
import com.nightgals.common.PageResponse;
import com.nightgals.discovery.dto.MemberCardResponse;
import com.nightgals.live.LiveSessionRepository;
import com.nightgals.media.MediaAsset;
import com.nightgals.media.MediaRepository;
import com.nightgals.media.MediaStatus;
import com.nightgals.media.MediaType;
import com.nightgals.profile.Profile;
import com.nightgals.profile.ProfileRepository;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The scroll feed.
 *
 * <p>Free to any verified member: a card carries enough to judge interest, and
 * the counts of what is behind the paywall. Paying is what turns those counts
 * into content.
 */
@Service
@RequiredArgsConstructor
public class FeedService {

    /** Matches no real user, so an anonymous feed excludes nobody. */
    private static final UUID ANONYMOUS = new UUID(0L, 0L);

    private final ProfileRepository profileRepository;
    private final MediaRepository mediaRepository;
    private final LiveSessionRepository liveSessionRepository;
    private final EntitlementService entitlementService;

    /**
     * @param viewer the caller, or null when anonymous - the feed is public so
     *               people can see who is here before signing up
     */
    @Transactional(readOnly = true)
    public PageResponse<MemberCardResponse> feed(User viewer, String city, Pageable pageable) {
        Page<Profile> page = profileRepository.findFeed(
                // A sentinel rather than null: the query only uses this to exclude
                // the caller's own card, and a null bind parameter here would hit
                // the same Postgres type-inference problem as the city filter.
                viewer == null ? ANONYMOUS : viewer.getId(),
                city == null || city.isBlank() ? null : city.trim().toLowerCase(java.util.Locale.ROOT),
                pageable);

        List<UUID> userIds = page.getContent().stream().map(p -> p.getUser().getId()).toList();
        if (userIds.isEmpty()) {
            return PageResponse.from(page, p -> null);
        }

        // Three batch queries for the whole page rather than three per card.
        Set<UUID> unlocked = entitlementService.unlockedAmong(viewer, userIds);
        Set<UUID> liveHosts = Set.copyOf(liveSessionRepository.findLiveHostIds(userIds));
        Map<UUID, List<MediaAsset>> mediaByUser = approvedMediaFor(userIds);

        return PageResponse.from(page, profile -> {
            UUID userId = profile.getUser().getId();
            List<MediaAsset> media = mediaByUser.getOrDefault(userId, List.of());
            boolean isUnlocked = unlocked.contains(userId);

            List<String> freePhotoUrls = new ArrayList<>();
            List<String> freeVideoUrls = new ArrayList<>();
            int lockedPhotos = 0;
            int lockedVideos = 0;

            for (MediaAsset asset : media) {
                // An unlocked viewer has nothing left behind the paywall, so every
                // item is playable for them.
                boolean visible = asset.isFree() || isUnlocked;
                String url = "/api/v1/media/" + asset.getId() + "/file";

                if (asset.getType() == MediaType.PHOTO) {
                    if (visible) freePhotoUrls.add(url); else lockedPhotos++;
                } else {
                    if (visible) freeVideoUrls.add(url); else lockedVideos++;
                }
            }

            return MemberCardResponse.of(profile, freePhotoUrls, freeVideoUrls,
                    lockedPhotos, lockedVideos, liveHosts.contains(userId), isUnlocked);
        });
    }

    private Map<UUID, List<MediaAsset>> approvedMediaFor(List<UUID> userIds) {
        Map<UUID, List<MediaAsset>> byUser = new HashMap<>();
        for (MediaAsset asset : mediaRepository.findApprovedForUsers(userIds, MediaStatus.APPROVED)) {
            byUser.computeIfAbsent(asset.getUser().getId(), k -> new ArrayList<>()).add(asset);
        }
        return byUser;
    }
}
