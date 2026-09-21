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
         * Longest a broadcast may stay LIVE before {@link
         * com.nightgals.live.LiveOverrunJob} ends it without being asked to.
         *
         * <p>Nothing else ever closes a room a creator walked away from - the
         * status stays LIVE, and whatever streaming provider is wired in keeps
         * billing minutes, until this catches it. Six hours by default: long
         * enough that no legitimate broadcast is cut short, short enough that a
         * forgotten one does not run all night.
         */
        Duration maxSessionLength) {

    public Duration reminderLeadTime() {
        return reminderLeadTime == null ? Duration.ofMinutes(30) : reminderLeadTime;
    }

    public Duration maxSessionLength() {
        return maxSessionLength == null ? Duration.ofHours(6) : maxSessionLength;
    }
}
