package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a creator pays the platform, and what it buys.
 *
 * <p>Every package covers both photos and video - the tier decides how much and
 * how visible, not what kind. The allowances live here rather than in the
 * database because they are a pricing decision: raising the Diamond video limit
 * should be a config change and a restart, not a migration.
 */
@ConfigurationProperties(prefix = "nightgals.creator-packages")
public record CreatorPackageProperties(

        /**
         * Master switch. When false, publishing needs no package and a flat
         * allowance applies - which is how the test suite and any deployment
         * that has not started charging creators run.
         */
        boolean enabled,

        /** Packages keyed by code: PRO, DIAMOND, BLACK_DIAMOND. */
        Map<String, Package> packages) {

    public record Package(

            /** Shown on the pricing card, e.g. "Black Diamond". */
            String label,

            /** One line under the label. */
            String tagline,

            /** Price in minor units. For XAF that is the price itself. */
            long priceMinor,

            /** How long it runs before it has to be bought again. Weekly. */
            Duration duration,

            /**
             * Premium videos this package allows.
             *
             * <p>The brief's headline number: "upload up to 10 premium videos".
             * Counted against what is currently posted, so deleting frees a slot.
             */
            int maxPremiumVideos,

            /**
             * Photos allowed. Not a tier differentiator in the brief - photos are
             * mostly the shop window - so this is generous on every package and
             * exists only to stop one account filling the bucket.
             */
            int maxPhotos,

            /**
             * Live reels this package allows at once: 3, 2, or 1.
             *
             * <p>A cap on what is showing, not on how many may ever be posted -
             * a reel clears itself within a day, so this is a limit on how much
             * of the landing page one creator holds at a time rather than a
             * quota she can exhaust.
             *
             * <p>This is the sharpest of the three tiers precisely because the
             * strip is a shared shop window. Videos and photos live on a
             * creator's own profile, where more of hers costs nobody else
             * anything; a reel sits on the front page next to everyone.
             */
            int maxReels,

            /**
             * Minutes of live broadcast per UTC day: 15, 45, or 120.
             *
             * <p>Per day, not per session. A creator can run one long stream or
             * six short ones; what is metered is the total.
             */
            int liveMinutesPerDay,

            /**
             * Where this package's creators sit in search and on the homepage.
             * Higher wins. Defaults to the code's own rank when left unset.
             */
            Integer searchPriority) {

        public boolean coversVideos() {
            return maxPremiumVideos > 0;
        }

        public boolean coversPhotos() {
            return maxPhotos > 0;
        }

        public boolean coversLive() {
            return liveMinutesPerDay > 0;
        }

        public boolean coversReels() {
            return maxReels > 0;
        }
    }

    /** Never null, so callers can iterate without a guard. */
    public Map<String, Package> packages() {
        return packages == null ? new LinkedHashMap<>() : packages;
    }
}
