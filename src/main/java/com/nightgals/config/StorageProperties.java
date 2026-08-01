package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

/**
 * Where uploaded files live, and what may be uploaded.
 *
 * <p>{@code provider} picks the implementation of
 * {@link com.nightgals.storage.StorageService}; nothing else in the app knows or
 * cares which one is in use.
 */
@ConfigurationProperties(prefix = "nightgals.storage")
public record StorageProperties(

        /**
         * {@code s3} for object storage, {@code local} for the filesystem.
         *
         * <p>{@code s3} covers both AWS and MinIO - it is the same protocol, and
         * only {@link S3#endpoint()} differs between them.
         */
        String provider,

        /** Root directory holding both the kyc/ and media/ subtrees. Local provider only. */
        String rootPath,

        /** Object-storage settings. Ignored by the local provider. */
        S3 s3,

        DataSize maxImageSize,
        DataSize maxVideoSize,
        List<String> allowedImageTypes,
        List<String> allowedVideoTypes,
        /** How long a signed download link stays valid. */
        Duration downloadUrlTtl,
        /** Delete KYC document files this long after a decision is recorded. */
        Duration kycRetention) {

    public boolean usesObjectStorage() {
        return "s3".equalsIgnoreCase(provider);
    }

    public record S3(

            /** The one bucket everything lives in, under kyc/ and media/ prefixes. */
            String bucket,

            /** e.g. eu-west-1. MinIO ignores it but the SDK still requires one. */
            String region,

            /**
             * Override for a non-AWS endpoint, e.g. {@code http://localhost:9000} for
             * MinIO. Leave blank against real S3 so the SDK resolves the regional
             * endpoint itself.
             */
            String endpoint,

            /**
             * Blank on AWS, where credentials come from the default provider chain -
             * an instance role or the environment, never a value checked into config.
             * MinIO needs them supplied.
             */
            String accessKey,
            String secretKey,

            /**
             * MinIO serves {@code endpoint/bucket/key}; AWS serves
             * {@code bucket.endpoint/key}. Must be true for MinIO.
             */
            boolean pathStyleAccess,

            /**
             * Create the bucket at startup if it is missing. Convenient locally,
             * and harmless in production where the bucket already exists - but the
             * deploying credentials may not be allowed to create buckets, so it is
             * a property rather than unconditional.
             */
            boolean autoCreateBucket) {
    }
}
