package com.nightgals.storage;

import com.nightgals.common.ApiException;
import com.nightgals.common.Hashing;
import com.nightgals.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores uploads on the local filesystem.
 *
 * <p>Files are given random names - the original filename is attacker-controlled
 * and is never used to build a path. Every resolved path is checked to be inside
 * the configured root so a crafted key cannot escape via {@code ../}.
 */
@Slf4j
@Service
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(StorageProperties properties) {
        this.root = Path.of(properties.rootPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("Local file storage rooted at {}", root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage root " + root, e);
        }
    }

    @Override
    public StoredFile store(MultipartFile file, String scope) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("empty_file", "Uploaded file is empty");
        }

        String extension = extensionOf(file.getOriginalFilename());
        String storageKey = scope + "/" + UUID.randomUUID() + extension;
        Path target = resolve(storageKey);

        try {
            Files.createDirectories(target.getParent());

            String checksum;
            try (InputStream in = file.getInputStream()) {
                checksum = Hashing.sha256(in);
            }
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            return new StoredFile(storageKey, file.getContentType(), file.getSize(), checksum);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store upload at " + storageKey, e);
        }
    }

    @Override
    public Resource load(String storageKey) {
        Path path = resolve(storageKey);
        if (!Files.isReadable(path)) {
            throw ApiException.notFound("File");
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", storageKey, e.getMessage());
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.exists(resolve(storageKey));
    }

    /** Resolves a key under the root, rejecting anything that escapes it. */
    private Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw ApiException.badRequest("invalid_key", "Invalid storage key");
        }
        return path;
    }

    private String extensionOf(String originalFilename) {
        String ext = StringUtils.getFilenameExtension(
                StringUtils.cleanPath(originalFilename == null ? "" : originalFilename));
        if (ext == null || ext.isBlank() || ext.length() > 5 || !ext.matches("[A-Za-z0-9]+")) {
            return "";
        }
        return "." + ext.toLowerCase(Locale.ROOT);
    }
}
