package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Scheduled broadcasts and the reminders that go with them. */
@ConfigurationProperties(prefix = "nightgals.live")
public record LiveProperties(

        /**
         * How long before a scheduled broadcast its followers are emailed.
         *
         * <p>Half an hour: long enough to be somewhere with signal, short enough
         * that the reminder is still about tonight.
         */
        Duration reminderLeadTime,

        /** Sweep that sends them. */
        String reminderCron,

        /**
         * The longest one broadcast may run before it is ended for the host.
         *
         * <p>Two hours. Creators start a broadcast and walk away from it, and a
         * room left open all day burns the provider minutes the whole platform
         * shares - an absent host is billed exactly like a present one.
         */
        Duration maxSessionLength,

        /** How often broadcasts past that limit are looked for. */
        String overrunCron) {

    public Duration reminderLeadTime() {
        return reminderLeadTime == null ? Duration.ofMinutes(30) : reminderLeadTime;
    }

    public Duration maxSessionLength() {
        return maxSessionLength == null || maxSessionLength.isZero() || maxSessionLength.isNegative()
                ? Duration.ofHours(2)
                : maxSessionLength;
    }
}
