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

        /**
         * Which {@link com.nightgals.billing.PaymentProvider} is wired in.
         *
         * <p>{@code auto} completes every purchase instantly and collects nothing -
         * the happy path, for demonstrating the product without a human confirming
         * each payment. {@code manual} leaves purchases PENDING for an
         * administrator to settle once money actually arrives.
         *
         * <p>Neither takes payment. A real integration adds a third value.
         */
        String provider,

        /** Default price and lifetime of unlocking a single profile. */
        ProfileUnlock profileUnlock,

        /** Subscription plans, keyed by the code clients send. */
        Map<String, Plan> plans) {

    /**
     * Unlocking one creator.
     *
     * <p>{@code priceMinor} is only a default. A creator who has set her own price
     * overrides it, within the bounds below - the floor stops a race to the bottom
     * that would make the commission worthless, the ceiling stops somebody pricing
     * themselves out by mistyping an amount.
     */
    public record ProfileUnlock(
            long priceMinor,
            Duration duration,
            Long minPriceMinor,
            Long maxPriceMinor) {

        public long floor() {
            return minPriceMinor == null ? 0L : minPriceMinor;
        }

        public long ceiling() {
            return maxPriceMinor == null ? Long.MAX_VALUE : maxPriceMinor;
        }
    }

    public record Plan(String label, long priceMinor, Duration duration) {
    }
}
