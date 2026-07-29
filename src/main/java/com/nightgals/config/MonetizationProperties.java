package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * What is free and what costs money.
 *
 * <p>Prices are in the currency's minor unit (cents, or for KES the shilling
 * cent) so no rounding ever happens in floating point.
 */
@ConfigurationProperties(prefix = "nightgals.monetization")
public record MonetizationProperties(

        /** Master switch. When false every entitlement check passes and nothing is gated. */
        boolean enabled,

        /** ISO 4217, e.g. KES. */
        String currency,

        /** Price and lifetime of unlocking a single profile. */
        ProfileUnlock profileUnlock,

        /** Subscription plans, keyed by the code clients send. */
        Map<String, Plan> plans) {

    public record ProfileUnlock(long priceMinor, Duration duration) {
    }

    public record Plan(String label, long priceMinor, Duration duration) {
    }
}
