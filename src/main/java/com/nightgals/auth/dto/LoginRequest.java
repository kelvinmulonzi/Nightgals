package com.nightgals.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Credentials")
public record LoginRequest(
        @Schema(example = "amina@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String email,
        @Schema(example = "correct-horse-9", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String password) {
}
