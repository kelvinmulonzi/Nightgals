package com.nightgals.storage;

import com.nightgals.common.ApiException;
import com.nightgals.common.Hashing;
import com.nightgals.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores uploads in an S3 bucket - AWS in production, MinIO locally.
 *
 * <p>Objects are written with no public ACL and are never served directly. Every
 * read goes through {@link com.nightgals.media.MediaService}, which re-checks the
 * paywall on each request; a presigned URL could not, because once handed out it
 * works for anyone holding it until it expires.
 *
 * <p>Keys are random UUIDs under a {@code kyc/} or {@code media/} prefix. The
 * client's filename is attacker-controlled and never becomes part of a key.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "nightgals.storage.provider", havingValue = "s3")
public class S3StorageService implements StorageService {

    private final S3Client s3;
    private final StorageProperties.S3 config;

    public S3StorageService(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.config = properties.s3();
    }

    /**
     * Confirms the bucket is reachable before the first upload rather than at it.
     *
     * <p>A misconfigured bucket that only shows up when somebody uploads their
     * passport is a bad way to find out.
     */
    @PostConstruct
    void verifyBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(config.bucket()).build());
            log.info("Object storage ready: bucket {}", config.bucket());
        } catch (NoSuchBucketException e) {
            if (!config.autoCreateBucket()) {
                throw new IllegalStateException("S3 bucket '" + config.bucket()
                        + "' does not exist. Create it, or set nightgals.storage.s3.auto-create-bucket=true.");
            }
            s3.createBucket(CreateBucketRequest.builder().bucket(config.bucket()).build());
            log.info("Created object storage bucket {}", config.bucket());
        } catch (S3Exception e) {
            throw new IllegalStateException("Cannot reach S3 bucket '" + config.bucket()
                    + "': " + e.getMessage(), e);
        }
    }

    @Override
    public StoredFile store(MultipartFile file, String scope) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("empty_file", "Uploaded file is empty");
        }

        String storageKey = scope + "/" + UUID.randomUUID() + extensionOf(file.getOriginalFilename());

        try {
            // Hashed from a separate stream before upload: the checksum is what
            // detects a duplicate document, so it has to be of the bytes actually
            // received rather than anything the client asserted.
            String checksum;
            try (InputStream in = file.getInputStream()) {
                checksum = Hashing.sha256(in);
            }

            try (InputStream in = file.getInputStream()) {
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(config.bucket())
                                .key(storageKey)
                                .contentType(file.getContentType())
                                .contentLength(file.getSize())
                                .build(),
                        // Length given explicitly so the SDK streams rather than
                        // buffering a 100MB video into heap.
                        RequestBody.fromInputStream(in, file.getSize()));
            }

            return new StoredFile(storageKey, file.getContentType(), file.getSize(), checksum);
        } catch (IOException | S3Exception e) {
            throw new IllegalStateException("Failed to store upload at " + storageKey, e);
        }
    }

    @Override
    public Resource load(String storageKey) {
        try {
            // The response stream is handed straight to the servlet output, so a
            // large video is never held in memory.
            return new InputStreamResource(s3.getObject(GetObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(storageKey)
                    .build()));
        } catch (NoSuchKeyException e) {
            throw ApiException.notFound("File");
        } catch (S3Exception e) {
            throw new IllegalStateException("Failed to read " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(storageKey)
                    .build());
        } catch (S3Exception e) {
            // Purge jobs must stay idempotent and must not fail a whole sweep on
            // one object.
            log.warn("Could not delete {}: {}", storageKey, e.getMessage());
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(storageKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            log.warn("Could not stat {}: {}", storageKey, e.getMessage());
            return false;
        }
    }

    /** Kept only as a content hint; never used to build a key. */
    private String extensionOf(String originalFilename) {
        String ext = StringUtils.getFilenameExtension(
                StringUtils.cleanPath(originalFilename == null ? "" : originalFilename));
        if (ext == null || ext.isBlank() || ext.length() > 5 || !ext.matches("[A-Za-z0-9]+")) {
            return "";
        }
        return "." + ext.toLowerCase(Locale.ROOT);
    }
}
