package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * What is free and what costs money.
 *
 * <p>Prices are in the currency's minor unit, so no rounding ever happens in
 * floating point. For XAF - which has no minor unit - that is simply the price.
 * {@link com.nightgals.common.Money} is the only thing that knows the
 * difference; nothing here should divide by 100.
 */
@ConfigurationProperties(prefix = "nightgals.monetization")
public record MonetizationProperties(

        /** Master switch. When false every entitlement check passes and nothing is gated. */
        boolean enabled,

        /** ISO 4217. XAF. */
        String currency,

        /**
         * Which {@link com.nightgals.billing.PaymentProvider} is wired in.
         *
         * <p>{@code auto} completes every purchase instantly and collects nothing -
         * the happy path, for demonstrating the product without a human confirming
         * each payment. {@code manual} leaves purchases PENDING for an
         * administrator to settle once money actually arrives.
         */
        String provider,

        /** Shown when the provider is manual. */
        String manualPaymentInstructions,

        /** Bounds and fallbacks for the price a creator puts on one item. */
        ItemPricing itemPricing,

        /**
         * How long a brand-new account gets everything for nothing.
         *
         * <p>Covers both sides: a viewer sees premium content, a creator may
         * publish, without either of them paying. Null or zero switches it off.
         */
        Duration freeTrial,

        Referral referral) {

    /**
     * What a viewer pays for one video or one broadcast.
     *
     * <p>The creator sets it. These are the fallback for something she never
     * priced, and the range she has to stay inside - the floor so the commission
     * still covers the payment fee, the ceiling to catch a mistyped extra zero.
     */
    public record ItemPricing(
            long defaultPriceMinor,
            Long minPriceMinor,
            Long maxPriceMinor,
            /** How long access lasts. Null means it never expires. */
            Duration accessDuration) {

        public long floor() {
            return minPriceMinor == null ? 0L : minPriceMinor;
        }

        public long ceiling() {
            return maxPriceMinor == null ? Long.MAX_VALUE : maxPriceMinor;
        }
    }

    public record Referral(
            /** Credited to the referrer when the referred account buys its first package. */
            long bonusMinor) {
    }

    public boolean trialEnabled() {
        return freeTrial != null && !freeTrial.isZero() && !freeTrial.isNegative();
    }
}
