package com.nightgals.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Schema(description = "Standard error envelope returned by every endpoint")
public record ErrorResponse(
        @Schema(example = "2026-07-29T10:15:30Z") Instant timestamp,
        @Schema(example = "400") int status,
        @Schema(description = "Stable machine-readable code", example = "validation_failed") String code,
        @Schema(example = "Request validation failed") String message,
        @Schema(description = "Field-level validation errors, keyed by field name")
        Map<String, List<String>> fieldErrors) {

    public static ErrorResponse of(int status, String code, String message) {
        return new ErrorResponse(Instant.now(), status, code, message, null);
    }

    public static ErrorResponse of(int status, String code, String message, Map<String, List<String>> fieldErrors) {
        return new ErrorResponse(Instant.now(), status, code, message, fieldErrors);
    }
}
