package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/** The economics of creator payouts. */
@ConfigurationProperties(prefix = "nightgals.earnings")
public record EarningsProperties(

        /** Platform's cut, as a percentage of gross. 30 means the creator keeps 70%. */
        BigDecimal commissionPercent,

        /**
         * How long an entry stays PENDING before it can be paid out. Gives a
         * window in which a refund can reverse it.
         */
        Duration holdPeriod,

        /** Creators cannot request less than this, to keep transfer fees sane. */
        long minimumPayoutMinor) {
}
