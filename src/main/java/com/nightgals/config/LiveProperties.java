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
        String reminderCron) {

    public Duration reminderLeadTime() {
        return reminderLeadTime == null ? Duration.ofMinutes(30) : reminderLeadTime;
    }
}
