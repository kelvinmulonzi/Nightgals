package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound email.
 *
 * <p>The SMTP connection itself is Spring Boot's ({@code spring.mail.*}); this is
 * everything about the messages we send with it.
 */
@ConfigurationProperties(prefix = "nightgals.mail")
public record NotificationProperties(

        /**
         * Master switch. When false nothing is sent and one-time codes are written
         * to the log instead, so the whole sign-in flow still works on a machine
         * with no outbound SMTP.
         */
        boolean enabled,

        /** Envelope sender. Must be an address the SMTP account is allowed to send as. */
        String from,

        /** Display name shown in the recipient's client. */
        String fromName,

        /** Where a link in an email should point, e.g. https://nightgals.com. */
        String appBaseUrl,

        /** Address a recipient is told to contact. */
        String supportEmail) {
}
