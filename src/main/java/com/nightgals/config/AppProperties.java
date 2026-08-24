package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "nightgals.app")
public record AppProperties(
        List<String> corsAllowedOrigins,
        /** Nobody under this age can complete verification. */
        int minimumAge,
        /** Pepper mixed into the document-number hash. Override in production. */
        String documentHashPepper,

        /**
         * Whether a creator must have identity documents approved before she can
         * publish and earn.
         *
         * <p>Off by default. With it off there is no document upload, no review
         * queue and no waiting: saving a profile is the whole of onboarding, and
         * the member goes straight to choosing a package. Approval is granted at
         * that moment, so every existing check on {@code isApproved()} keeps
         * working untouched - the flag changes <em>when</em> approval happens, not
         * how it is tested.
         *
         * <p>Turning it on later applies to accounts that have not been approved
         * yet. Anyone already auto-approved stays approved rather than being
         * retroactively locked out of content they have already sold.
         */
        boolean kycRequired) {
}
