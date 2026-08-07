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
         * <p><b>Superseded by {@link #providers}</b>, which takes several. Still
         * read when that is unset, so existing deployments and the test suite keep
         * working: a single value behaves as a one-element list.
         */
        String provider,

        /**
         * Every payment method on offer, comma-separated, in the order a picker
         * should show them: {@code momo,stripe}.
         *
         * <p>More than one runs at a time and the buyer chooses per checkout, so
         * this is a list rather than a switch. Known values are {@code momo},
         * {@code stripe}, {@code manual} and {@code auto} - the last two being
         * pre-launch scaffolding, and {@code auto} collecting no money at all.
         *
         * <p>Read by {@link com.nightgals.billing.PaymentProviderCondition} at bean
         * registration time as well as here, so a method left out of this list has
         * no beans at all rather than beans nobody can reach.
         */
        String providers,

        /**
         * Which method a checkout that names none should use.
         *
         * <p>Every client predating the picker sends no {@code method}, so this is
         * what they get. Defaults to the first entry of {@link #providers}.
         */
        String defaultProvider,

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

        Referral referral,

        /** What extra live minutes cost, when a broadcast needs to run longer. */
        LiveExtension liveExtension) {

    /**
     * Buying past the daily live allowance.
     *
     * <p>Priced per minute rather than in fixed blocks so the client can offer
     * whatever lengths make sense without the server having to agree in advance.
     * The daily cap is what stops a top-up from quietly replacing a package.
     */
    public record LiveExtension(
            /** Zero or absent switches extensions off entirely. */
            long pricePerMinuteMinor,
            /** The most that can be bought in one day, across all top-ups. */
            int maxMinutesPerDay) {
    }

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
