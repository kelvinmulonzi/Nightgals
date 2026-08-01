package com.nightgals.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * The S3 client, built only when object storage is the configured provider.
 *
 * <p>Deliberately one configuration for both AWS and MinIO. They speak the same
 * protocol, so pointing at MinIO locally exercises the same code path that runs
 * in production - the alternative, a filesystem store in development and S3 in
 * production, is how "works on my machine" storage bugs happen.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "nightgals.storage.provider", havingValue = "s3")
public class S3Config {

    @Bean
    public S3Client s3Client(StorageProperties properties) {
        StorageProperties.S3 config = require(properties);
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(config.region()))
                .credentialsProvider(credentials(config))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.pathStyleAccess())
                        .build());

        if (hasText(config.endpoint())) {
            builder.endpointOverride(URI.create(config.endpoint()));
            log.info("S3 storage pointed at {} (bucket {})", config.endpoint(), config.bucket());
        } else {
            log.info("S3 storage in {} (bucket {})", config.region(), config.bucket());
        }
        return builder.build();
    }

    /**
     * Signs time-limited URLs.
     *
     * <p>Not used for serving media today - the app streams bytes itself so the
     * paywall is enforced on every request, which a presigned URL cannot do once
     * it has been handed out. It is here for the cases where handing out a link
     * is the right answer, such as a creator exporting her own back catalogue.
     */
    @Bean
    public S3Presigner s3Presigner(StorageProperties properties) {
        StorageProperties.S3 config = require(properties);
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(config.region()))
                .credentialsProvider(credentials(config));

        if (hasText(config.endpoint())) {
            builder.endpointOverride(URI.create(config.endpoint()));
        }
        return builder.build();
    }

    /**
     * Static keys when they are configured (MinIO, and any deployment still using
     * long-lived keys), otherwise the default chain - instance role, container
     * role, environment - which is what a real AWS deployment should be using.
     */
    private static software.amazon.awssdk.auth.credentials.AwsCredentialsProvider credentials(
            StorageProperties.S3 config) {
        if (hasText(config.accessKey()) && hasText(config.secretKey())) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.accessKey(), config.secretKey()));
        }
        return DefaultCredentialsProvider.create();
    }

    private static StorageProperties.S3 require(StorageProperties properties) {
        StorageProperties.S3 config = properties.s3();
        if (config == null || !hasText(config.bucket())) {
            throw new IllegalStateException(
                    "nightgals.storage.provider is s3 but nightgals.storage.s3.bucket is not set");
        }
        return config;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
