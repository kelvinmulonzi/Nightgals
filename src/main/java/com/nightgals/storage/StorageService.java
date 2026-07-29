package com.nightgals.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * Everything the app needs from a file store. The local implementation writes
 * under nightgals.storage.root-path; swapping in object storage later means
 * adding one class, not touching callers.
 */
public interface StorageService {

    /**
     * @param scope logical folder, e.g. {@code kyc/<submissionId>} or {@code media/<userId>}
     */
    StoredFile store(MultipartFile file, String scope);

    Resource load(String storageKey);

    /** No-op if the key is already gone, so purge jobs stay idempotent. */
    void delete(String storageKey);

    boolean exists(String storageKey);
}
