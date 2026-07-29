package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "nightgals.app")
public record AppProperties(
        List<String> corsAllowedOrigins,
        /** Nobody under this age can complete verification. */
        int minimumAge,
        /** Pepper mixed into the document-number hash. Override in production. */
        String documentHashPepper) {
}
