package com.nightgals.storage;

/** What a StorageService hands back after accepting an upload. */
public record StoredFile(
        String storageKey,
        String contentType,
        long sizeBytes,
        String checksumSha256) {
}
