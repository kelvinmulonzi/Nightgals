package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

/**
 * Local-filesystem storage settings. When object storage is introduced, only
 * the StorageService implementation changes - callers stay the same.
 */
@ConfigurationProperties(prefix = "nightgals.storage")
public record StorageProperties(
        /** Root directory holding both the kyc/ and media/ subtrees. */
        String rootPath,
        DataSize maxImageSize,
        DataSize maxVideoSize,
        List<String> allowedImageTypes,
        List<String> allowedVideoTypes,
        /** How long a signed download link stays valid. */
        Duration downloadUrlTtl,
        /** Delete KYC document files this long after a decision is recorded. */
        Duration kycRetention) {
}
