package com.nightgals.discovery;

import com.nightgals.billing.CreatorPackageService;
import com.nightgals.billing.EntitlementService;
import com.nightgals.billing.ItemPricingService;
import com.nightgals.common.PageResponse;
import com.nightgals.discovery.dto.CityCountResponse;
import com.nightgals.discovery.dto.MemberCardResponse;
import com.nightgals.discovery.dto.VideoCardResponse;
import com.nightgals.live.LiveSessionRepository;
import com.nightgals.media.ContentTier;
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
    public PageResponse<MemberCardResponse> feed(User viewer, String q, String city, String gender,
                                                 Integer minAge, Integer maxAge,
                                                 Boolean liveOnly, Boolean premiumOnly,
                                                 Pageable pageable) {
        // Swapped rather than rejected: someone dragging a range slider past
        // itself means the range, not an error page.
        if (minAge != null && maxAge != null && minAge > maxAge) {
            Integer swap = minAge;
            minAge = maxAge;
            maxAge = swap;
        }

        Page<Profile> page = profileRepository.findFeed(
                // A sentinel rather than null: the query only uses this to exclude
                // the caller's own card, and a null bind parameter here would hit
                // the same Postgres type-inference problem as the city filter.
                viewer == null ? ANONYMOUS : viewer.getId(),
                blankToNull(q),
                city == null || city.isBlank() ? null : city.trim().toLowerCase(java.util.Locale.ROOT),
                blankToNull(gender),
                minAge,
                maxAge,
                // Filtered in the query rather than over the returned page. The
                // client used to hide non-live cards after the fact, which meant
                // "Live only" answered "nobody is live" whenever the people on
                // air happened to sit past the first page of results.
                liveOnly,
                premiumOnly,
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
            // The photo she actually chose to lead with. Carried separately from
            // freePhotoUrls because "first free photo" is whatever happens to sort
            // first, which is not the same thing and changes as she posts.
            String profilePhotoUrl = null;

            for (MediaAsset asset : media) {
                if (viewable.contains(asset.getId())) {
                    String url = "/api/v1/media/" + asset.getId() + "/file";
                    if (asset.getType() == MediaType.PHOTO) {
                        freePhotoUrls.add(url);
                        if (asset.isPrimary()) {
                            profilePhotoUrl = url;
                        }
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

            // The picture she uploaded wins over whichever gallery photo happens to
            // carry the primary flag. The card used to ignore the avatar entirely,
            // so setting one changed her profile page and left Discover showing
            // the old face - which reads as "my photo did not save".
            if (profile.hasAvatar()) {
                profilePhotoUrl = "/api/v1/members/" + userId + "/photo";
            }

            return MemberCardResponse.of(
                    profile,
                    profilePhotoUrl,
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

    /**
     * Every video on the platform, newest first.
     *
     * <p>Discover asks "who is here"; this asks "what is there to watch". They are
     * different questions and a creator's videos were reachable only by finding
     * her first, which means the clip somebody would have paid for was three taps
     * and a guess away.
     *
     * <p>Ordered by recency alone, deliberately - not by package rank the way
     * Discover is. Package priority is sold on listings of <em>people</em>, and
     * applying it here would stack one Black Diamond creator's whole catalogue
     * above everybody else's newest work, which is a worse wall for viewers and
     * not what was promised.
     *
     * <p>{@code tier} filters what kind of clip is listed; it is not a filter on
     * what this caller can watch. Asking for EXCLUSIVE returns paywalled clips
     * whether or not they have been paid for - locked ones simply come back with
     * a price and no URL, which is what makes them sellable.
     *
     * @param viewer the caller, or null when anonymous
     * @param tier   FREE, EXCLUSIVE, or null for both
     */
    @Transactional(readOnly = true)
    public PageResponse<VideoCardResponse> videos(User viewer, ContentTier tier, Pageable pageable) {
        Page<MediaAsset> page = mediaRepository.findVideoFeed(
                tier == null ? List.of(ContentTier.values()) : List.of(tier), pageable);

        List<MediaAsset> videos = page.getContent();
        if (videos.isEmpty()) {
            return PageResponse.from(page, v -> null);
        }

        // Batch queries for the whole page, as the member feed does. The same
        // creator can hold several of these tiles, hence distinct().
        List<UUID> userIds = videos.stream().map(v -> v.getUser().getId()).distinct().toList();
        Set<UUID> viewable = entitlementService.viewableAmong(viewer, videos);
        Map<UUID, Profile> profiles = new HashMap<>();
        for (Profile profile : profileRepository.findByUserIdIn(userIds)) {
            profiles.put(profile.getUser().getId(), profile);
        }
        Map<UUID, UUID> primaryPhotos = new HashMap<>();
        for (MediaAsset photo : mediaRepository.findPrimaryForUsers(userIds, MediaStatus.APPROVED)) {
            primaryPhotos.put(photo.getUser().getId(), photo.getId());
        }

        return PageResponse.from(page, asset -> {
            UUID userId = asset.getUser().getId();
            Profile profile = profiles.get(userId);
            boolean unlocked = viewable.contains(asset.getId());
            // Priced even when it is not locked for this caller: the owner and
            // anyone who already paid still see what it costs.
            long price = pricing.priceOf(asset);

            return new VideoCardResponse(
                    asset.getId(),
                    userId,
                    asset.getUser().getUsername(),
                    profile == null ? null : profile.getDisplayName(),
                    creatorPhoto(userId, profile, primaryPhotos),
                    asset.getTier(),
                    unlocked ? "/api/v1/media/" + asset.getId() + "/file" : null,
                    asset.getCaption(),
                    asset.getContentType(),
                    asset.getSizeBytes(),
                    !unlocked,
                    unlocked ? null : price,
                    unlocked ? null : pricing.display(price),
                    pricing.currency(),
                    asset.getCreatedAt());
        });
    }

    /**
     * A face for the creator beside her clip.
     *
     * <p>Same order of preference as her profile page: the picture she uploaded,
     * then the gallery photo flagged primary, which is where profile pictures
     * lived before they had a field of their own. Null means she has neither and
     * the client draws a placeholder.
     */
    private String creatorPhoto(UUID userId, Profile profile, Map<UUID, UUID> primaryPhotos) {
        if (profile != null && profile.hasAvatar()) {
            return "/api/v1/members/" + userId + "/photo";
        }
        UUID primary = primaryPhotos.get(userId);
        return primary == null ? null : "/api/v1/media/" + primary + "/file";
    }

    private Map<UUID, List<MediaAsset>> groupByUser(List<MediaAsset> media) {
        Map<UUID, List<MediaAsset>> byUser = new HashMap<>();
        for (MediaAsset asset : media) {
            byUser.computeIfAbsent(asset.getUser().getId(), k -> new ArrayList<>()).add(asset);
        }
        return byUser;
    }

    /**
     * The city shortcuts beside the filters.
     *
     * <p>Read straight from the same population the feed draws from, so a
     * shortcut always lands on the number it advertised.
     */
    @Transactional(readOnly = true)
    public List<CityCountResponse> popularCities(int limit) {
        return profileRepository.findPopularCities(Math.clamp(limit, 1, 50)).stream()
                .map(row -> new CityCountResponse((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    /**
     * A search box that was typed in and then cleared arrives as "", which is
     * not the same request as "no filter" unless we say so here.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
