package com.nightgals.storage;

import com.nightgals.common.ApiException;
import com.nightgals.config.StorageProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;

/**
 * Content-type and size checks applied before anything touches the disk.
 *
 * <p>The declared content type is client-supplied and therefore untrusted; it is
 * checked because it is cheap, not because it is proof. A production deployment
 * should also sniff magic bytes and run uploads through a malware scanner.
 */
@Component
public class UploadValidator {

    private final StorageProperties properties;

    public UploadValidator(StorageProperties properties) {
        this.properties = properties;
    }

    public void validateImage(MultipartFile file) {
        validate(file, properties.allowedImageTypes(), properties.maxImageSize(), "image");
    }

    public void validateVideo(MultipartFile file) {
        validate(file, properties.allowedVideoTypes(), properties.maxVideoSize(), "video");
    }

    private void validate(MultipartFile file, List<String> allowedTypes, DataSize maxSize, String label) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("empty_file", "No " + label + " was uploaded");
        }
        if (file.getSize() > maxSize.toBytes()) {
            throw ApiException.badRequest("file_too_large",
                    "Maximum " + label + " size is " + maxSize.toMegabytes() + "MB");
        }
        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!allowedTypes.contains(contentType)) {
            throw ApiException.badRequest("unsupported_type",
                    "Unsupported " + label + " type. Allowed: " + String.join(", ", allowedTypes));
        }
    }
}
