package com.nightgals.billing;

import com.nightgals.billing.dto.CreatorPackageResponse;
import com.nightgals.billing.dto.CreatorPackageStatusResponse;
import com.nightgals.common.ApiException;
import com.nightgals.config.CreatorPackageProperties;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaRepository;
import com.nightgals.media.MediaType;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The supply side: what a creator is allowed to publish and broadcast.
 *
 * <p>Passing identity verification earns the right to be on the platform. A
 * package earns the right to use it, and decides three things:
 *
 * <ul>
 *   <li>how many <b>premium videos</b> she may have posted at once
 *   <li>how many <b>minutes of live</b> she may broadcast per day
 *   <li>where she <b>ranks</b> in search and on the homepage
 * </ul>
 *
 * <p>Every package covers photos and video both - the tier is about volume and
 * visibility, not about media type. That is a change from the set it replaced,
 * where silver deliberately excluded photos.
 *
 * <p>Allowances count what is currently posted, not a lifetime total. Deleting a
 * video frees a slot, which is the behaviour anybody expects from a limit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorPackageService {

    /**
     * What a creator may post when packages are switched off entirely, and how
     * long she may broadcast. Generous on purpose: with the feature off these
     * are a sanity bound, not a product.
     */
    private static final int UNMETERED_VIDEOS = 20;
    private static final int UNMETERED_PHOTOS = 50;
    private static final int UNMETERED_LIVE_MINUTES = 240;
    private static final int UNMETERED_REELS = 10;

    private final CreatorPackageRepository packageRepository;
    private final MediaRepository mediaRepository;
    private final CreatorPackageProperties properties;
    private final MonetizationProperties monetization;

    // ---------------------------------------------------------------- catalogue

    /** The three cards on the pricing page, cheapest first. */
    public List<CreatorPackageResponse> catalogue() {
        return properties.packages().entrySet().stream()
                .filter(e -> isKnownCode(e.getKey()))
                .map(e -> CreatorPackageResponse.of(
                        CreatorPackageCode.valueOf(e.getKey().toUpperCase(Locale.ROOT)),
                        e.getValue(),
                        monetization.currency()))
                .sorted(Comparator.comparingLong(CreatorPackageResponse::priceMinor))
                .toList();
    }

    public boolean packagesRequired() {
        return properties.enabled();
    }

    // ---------------------------------------------------------------- state

    @Transactional(readOnly = true)
    public Optional<CreatorPackage> activeFor(UUID creatorId) {
        return packageRepository.findActive(creatorId, Instant.now());
    }

    /**
     * Where this creator sits in search. Higher wins; no package is zero.
     *
     * <p>A creator on her free trial ranks as if she held the entry package -
     * enough to be findable while she is trying the product, not enough to
     * outrank somebody paying.
     */
    @Transactional(readOnly = true)
    public int searchPriorityOf(User creator) {
        if (!properties.enabled()) {
            return 0;
        }
        return activeFor(creator.getId())
                .map(held -> priorityOf(held.getPackageCode()))
                .orElseGet(() -> creator.isOnTrial() ? 1 : 0);
    }

    public int priorityOf(CreatorPackageCode code) {
        CreatorPackageProperties.Package config = properties.packages().get(code.name());
        return config != null && config.searchPriority() != null
                ? config.searchPriority()
                : code.rank();
    }

    /** What the studio shows a creator: her package, and how much of it is left. */
    @Transactional(readOnly = true)
    public CreatorPackageStatusResponse status(User creator) {
        int usedPhotos = (int) mediaRepository.countByUserIdAndType(creator.getId(), MediaType.PHOTO);
        int usedVideos = premiumVideosPosted(creator.getId());

        if (!properties.enabled()) {
            return CreatorPackageStatusResponse.unmetered(
                    usedPhotos, UNMETERED_PHOTOS, usedVideos, UNMETERED_VIDEOS, UNMETERED_LIVE_MINUTES);
        }

        Optional<CreatorPackage> active = activeFor(creator.getId());
        if (active.isEmpty()) {
            // A trial creator publishes freely; she just has nothing to renew yet.
            return creator.isOnTrial()
                    ? CreatorPackageStatusResponse.onTrial(
                            creator.getTrialEndsAt(), usedPhotos, usedVideos, catalogue())
                    : CreatorPackageStatusResponse.none(usedPhotos, usedVideos, catalogue());
        }

        CreatorPackage held = active.get();
        CreatorPackageProperties.Package config = configFor(held.getPackageCode());
        return CreatorPackageStatusResponse.active(
                held.getPackageCode(),
                config.label(),
                config.maxPhotos(), usedPhotos,
                config.maxPremiumVideos(), usedVideos,
                config.liveMinutesPerDay(),
                priorityOf(held.getPackageCode()),
                held.getExpiresAt(),
                catalogue());
    }

    // ---------------------------------------------------------------- the gate

    /**
     * The check every upload passes through.
     *
     * <p>Photos and premium videos are metered separately, and a free item is not
     * metered at all: the shop window is what sells the paid content, so charging
     * a creator's allowance for it would be exactly backwards.
     *
     * @throws ApiException 402 when paying or upgrading is what unblocks this,
     *                      409 when deleting something is
     */
    /**
     * The package gate on its own, with no per-type allowance behind it.
     *
     * <p>For things a package covers but does not meter - a reel is a short
     * advert that expires in a day, so counting it against a photo or video
     * limit would be meaningless. What matters is that she is a paying creator
     * at all.
     *
     * @throws ApiException 402 when there is no package and no trial running
     */
    @Transactional(readOnly = true)
    public void requireActivePackage(User creator) {
        if (!properties.enabled()) {
            return;
        }
        if (activeFor(creator.getId()).isPresent() || creator.isOnTrial()) {
            return;
        }
        throw ApiException.paymentRequired(
                "Choose a package before you post. Pro, Diamond and Black Diamond all "
                + "cover reels, photos and video - they differ on how much, and how "
                + "visible you are.");
    }

    @Transactional(readOnly = true)
    public void requireCanPublish(User creator, MediaType type, ContentTier tier) {
        if (!properties.enabled()) {
            requireWithin(type, posted(creator, type, tier),
                    type == MediaType.PHOTO ? UNMETERED_PHOTOS : UNMETERED_VIDEOS, "the current limit");
            return;
        }

        // The trial is full access: publishing included, and unmetered while it runs.
        if (activeFor(creator.getId()).isEmpty()) {
            if (creator.isOnTrial()) {
                return;
            }
            throw ApiException.paymentRequired(
                    "Choose a package before you post. Pro, Diamond and Black Diamond all "
                    + "cover photos and video - they differ on how much, and how visible you are.");
        }

        CreatorPackage held = activeFor(creator.getId()).orElseThrow();
        CreatorPackageProperties.Package config = configFor(held.getPackageCode());

        // Free items are the shop window and cost a creator nothing to post.
        if (tier == ContentTier.FREE && type == MediaType.VIDEO) {
            return;
        }

        int limit = type == MediaType.PHOTO ? config.maxPhotos() : config.maxPremiumVideos();
        requireWithin(type, posted(creator, type, tier), limit, config.label());
    }

    // ---------------------------------------------------------------- granting

    /**
     * Activates a package on a settled purchase.
     *
     * <p>Renewing the same package extends the row already in play. Inserting a
     * second one was tried and is subtly wrong: it would start where the old one
     * ends, putting its {@code startsAt} in the future and hiding it from
     * {@link CreatorPackageRepository#findActive} - so a creator who had just
     * paid would be shown the old expiry and told she renews sooner than she does.
     *
     * <p>Buying a <em>different</em> package does insert a row. The two overlap and
     * {@code findActive} orders by expiry, so the longer-running cover wins: an
     * upgrade applies at once, and a downgrade does not claw back time already
     * paid for.
     */
    @Transactional
    public CreatorPackage grant(User creator, CreatorPackageCode code, Purchase purchase) {
        CreatorPackageProperties.Package config = configFor(code);
        Instant now = Instant.now();

        Optional<CreatorPackage> current = packageRepository.findActive(creator.getId(), now);
        if (current.isPresent() && current.get().getPackageCode() == code) {
            CreatorPackage held = current.get();
            held.setExpiresAt(held.getExpiresAt().plus(config.duration()));
            log.info("Creator {} renewed {} until {}", creator.getId(), code, held.getExpiresAt());
            return held;
        }

        CreatorPackage granted = packageRepository.save(CreatorPackage.builder()
                .creator(creator)
                .packageCode(code)
                .startsAt(now)
                .expiresAt(now.plus(config.duration()))
                .purchase(purchase)
                .build());

        log.info("Creator {} now on {} until {}", creator.getId(), code, granted.getExpiresAt());
        return granted;
    }

    /** Resolves a client-supplied code, or explains what the valid ones are. */
    public CreatorPackageCode parseCode(String raw) {
        if (raw != null) {
            for (CreatorPackageCode code : CreatorPackageCode.values()) {
                if (code.name().equalsIgnoreCase(raw.trim())) {
                    return code;
                }
            }
        }
        throw ApiException.badRequest("unknown_package",
                "No such package. Valid codes: PRO, DIAMOND, BLACK_DIAMOND");
    }

    public CreatorPackageProperties.Package configFor(CreatorPackageCode code) {
        CreatorPackageProperties.Package config = properties.packages().get(code.name());
        if (config == null) {
            // Configuration and code have drifted. Better a clear 500 here than a
            // creator charged for a package with no allowance behind it.
            throw new IllegalStateException(
                    "Package " + code + " is not configured under nightgals.creator-packages.packages");
        }
        return config;
    }

    /**
     * Minutes of live this creator may broadcast per day.
     *
     * <p>Zero means she may not go live at all, which is what having no package
     * and no trial means.
     */
    @Transactional(readOnly = true)
    public int dailyLiveMinutesFor(User creator) {
        if (!properties.enabled()) {
            return UNMETERED_LIVE_MINUTES;
        }
        return activeFor(creator.getId())
                .map(held -> configFor(held.getPackageCode()).liveMinutesPerDay())
                .orElseGet(() -> creator.isOnTrial() ? UNMETERED_LIVE_MINUTES : 0);
    }

    /**
     * How many reels this creator may have showing at once.
     *
     * <p>Metered on the trial too, unlike photos and video, and deliberately: the
     * reel strip is the landing page, shared with everybody, and a trial account
     * posting into it without limit is the whole complaint this exists to answer.
     * Trial creators get the entry allowance rather than none - they can still
     * advertise, they just cannot take the front page.
     *
     * <p>Zero means she may not post at all, which is what somebody with no
     * package and no trial left gets.
     */
    @Transactional(readOnly = true)
    public int reelAllowanceFor(User creator) {
        if (!properties.enabled()) {
            return UNMETERED_REELS;
        }
        return activeFor(creator.getId())
                .map(held -> configFor(held.getPackageCode()).maxReels())
                .orElseGet(() -> creator.isOnTrial() ? entryReelAllowance() : 0);
    }

    /**
     * The smallest reel allowance any package offers, which is what a trial gets.
     *
     * <p>Read off the configured packages rather than hard-coded, so a deployment
     * that changes the tiers does not silently leave the trial on a number that
     * no longer matches the cheapest thing anyone can buy.
     */
    private int entryReelAllowance() {
        return properties.packages().values().stream()
                .mapToInt(CreatorPackageProperties.Package::maxReels)
                .filter(n -> n > 0)
                .min()
                .orElse(1);
    }

    /**
     * Whether this creator's work is shown to the public at all.
     *
     * <p>Publishing and being published are two different gates, and until now
     * only the first existed: a creator whose trial ran out could not post
     * anything new, but everything she had already posted stayed on the site
     * indefinitely. So the package bought her the right to add to a shop window
     * that stayed open whether she paid or not.
     *
     * <p>Now it closes. Her profile, gallery, videos, reels and live rooms leave
     * every public listing when she has neither a trial nor a package, and all of
     * it comes back the moment she buys one - nothing is deleted, exactly as with
     * a burned account.
     *
     * <p>The one thing this must never do is hide her from <em>herself</em>. Every
     * caller pairs it with an owner-or-staff check: a creator who cannot see her
     * own work has no way to understand why it vanished, and no way to value the
     * package that brings it back.
     */
    @Transactional(readOnly = true)
    public boolean isPubliclyVisible(User creator) {
        if (!properties.enabled()) {
            return true;
        }
        return creator.isOnTrial() || activeFor(creator.getId()).isPresent();
    }

    /** Whether the paid-visibility rule is switched on at all, for the SQL that cannot ask. */
    public boolean visibilityEnforced() {
        return properties.enabled();
    }

    // ---------------------------------------------------------------- internals

    /** Premium videos currently posted - free ones are not metered. */
    private int premiumVideosPosted(UUID creatorId) {
        return (int) mediaRepository.countByUserIdAndTypeAndTier(
                creatorId, MediaType.VIDEO, ContentTier.EXCLUSIVE);
    }

    private long posted(User creator, MediaType type, ContentTier tier) {
        if (type == MediaType.PHOTO) {
            return mediaRepository.countByUserIdAndType(creator.getId(), MediaType.PHOTO);
        }
        return tier == ContentTier.FREE
                ? mediaRepository.countByUserIdAndTypeAndTier(creator.getId(), type, ContentTier.FREE)
                : premiumVideosPosted(creator.getId());
    }

    private void requireWithin(MediaType type, long posted, int limit, String packageLabel) {
        String noun = type == MediaType.PHOTO ? "photos" : "premium videos";
        if (limit <= 0) {
            throw ApiException.paymentRequired(
                    packageLabel + " does not include " + noun + ". Upgrade for more room.");
        }
        if (posted >= limit) {
            throw ApiException.conflict("quota_exceeded",
                    packageLabel + " covers " + limit + " " + noun + " and you have " + posted
                    + ". Delete one, or upgrade for more room.");
        }
    }

    private boolean isKnownCode(String key) {
        for (CreatorPackageCode code : CreatorPackageCode.values()) {
            if (code.name().equalsIgnoreCase(key)) {
                return true;
            }
        }
        log.warn("Ignoring unknown creator package '{}' in configuration", key);
        return false;
    }
}
