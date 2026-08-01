package com.nightgals.discovery;

import com.nightgals.billing.CreatorPackageService;
import com.nightgals.billing.EntitlementService;
import com.nightgals.billing.ItemPricingService;
import com.nightgals.common.PageResponse;
import com.nightgals.discovery.dto.MemberCardResponse;
import com.nightgals.live.LiveSessionRepository;
import com.nightgals.media.MediaAsset;
import com.nightgals.media.MediaRepository;
import com.nightgals.media.MediaStatus;
import com.nightgals.media.MediaType;
import com.nightgals.profile.Profile;
import com.nightgals.profile.ProfileRepository;
import com.nightgals.social.FollowService;
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
 * <p>Free to look at, and ordered by package: Black Diamond first, then Diamond,
 * then Pro, then everybody else - which is the visibility those packages are
 * sold on. The ordering is done in the query rather than here, because sorting a
 * page after fetching it only sorts that page.
 *
 * <p>A card carries the cheapest thing on the profile rather than one price for
 * the person: with per-item pricing there is no single number, and "from 2,000"
 * is the honest way to say so.
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
    private final CreatorPackageService packageService;
    private final FollowService followService;
    private final ItemPricingService pricing;

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

        // Batch queries for the whole page rather than several per card.
        List<MediaAsset> allMedia = mediaRepository.findApprovedForUsers(userIds, MediaStatus.APPROVED);
        Set<UUID> viewable = entitlementService.viewableAmong(viewer, allMedia);
        Set<UUID> liveHosts = Set.copyOf(liveSessionRepository.findLiveHostIds(userIds));
        Set<UUID> followed = followService.followedAmong(viewer, userIds);
        Map<UUID, List<MediaAsset>> mediaByUser = groupByUser(allMedia);

        return PageResponse.from(page, profile -> {
            UUID userId = profile.getUser().getId();
            List<MediaAsset> media = mediaByUser.getOrDefault(userId, List.of());

            List<String> freePhotoUrls = new ArrayList<>();
            List<String> freeVideoUrls = new ArrayList<>();
            int lockedPhotos = 0;
            int lockedVideos = 0;
            Long cheapest = null;

            for (MediaAsset asset : media) {
                if (viewable.contains(asset.getId())) {
                    String url = "/api/v1/media/" + asset.getId() + "/file";
                    if (asset.getType() == MediaType.PHOTO) {
                        freePhotoUrls.add(url);
                    } else {
                        freeVideoUrls.add(url);
                    }
                    continue;
                }

                if (asset.getType() == MediaType.PHOTO) {
                    lockedPhotos++;
                } else {
                    lockedVideos++;
                }
                // "From X" needs the cheapest locked thing, not the first one.
                long price = pricing.priceOf(asset);
                if (cheapest == null || price < cheapest) {
                    cheapest = price;
                }
            }

            return MemberCardResponse.of(
                    profile,
                    freePhotoUrls,
                    freeVideoUrls,
                    lockedPhotos,
                    lockedVideos,
                    liveHosts.contains(userId),
                    followed.contains(userId),
                    cheapest,
                    cheapest == null ? null : pricing.display(cheapest),
                    packageService.searchPriorityOf(profile.getUser()),
                    pricing.currency());
        });
    }

    private Map<UUID, List<MediaAsset>> groupByUser(List<MediaAsset> media) {
        Map<UUID, List<MediaAsset>> byUser = new HashMap<>();
        for (MediaAsset asset : media) {
            byUser.computeIfAbsent(asset.getUser().getId(), k -> new ArrayList<>()).add(asset);
        }
        return byUser;
    }
}
