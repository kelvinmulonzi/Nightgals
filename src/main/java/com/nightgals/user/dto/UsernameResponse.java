package com.nightgals.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "The caller's handle after the change")
public record UsernameResponse(
        @Schema(example = "VelvetFalcon482") String username,
        @Schema(description = "When the handle may next be changed; null if it can be changed now")
        Instant changeableAfter) {
}
