package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Private 1-to-1 video calls. */
@ConfigurationProperties(prefix = "nightgals.calls")
public record CallProperties(

        boolean enabled,

        /**
         * The lengths a creator may put a price on, in minutes.
         *
         * <p>The brief calls these suggestions, so a creator offers whichever
         * subset she wants - but the set is bounded here rather than free-form,
         * because a booking UI cannot render an arbitrary number of options and a
         * 3-minute call is nobody's idea of a product.
         */
        List<Integer> allowedDurations,

        Long minPriceMinor,
        Long maxPriceMinor,

        /** How far ahead a call may be booked. */
        Duration maxLeadTime,

        /**
         * The least notice a booking needs.
         *
         * <p>Without it somebody books a call starting in ninety seconds and the
         * creator, who is not looking at her phone, misses it.
         */
        Duration minNotice) {

    public long floor() {
        return minPriceMinor == null ? 0L : minPriceMinor;
    }

    public long ceiling() {
        return maxPriceMinor == null ? Long.MAX_VALUE : maxPriceMinor;
    }

    public List<Integer> allowedDurations() {
        return allowedDurations == null ? List.of(5, 10, 15, 30, 45, 60) : allowedDurations;
    }
}
