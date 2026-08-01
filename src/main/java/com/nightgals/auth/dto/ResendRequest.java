package com.nightgals.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Send a fresh code for an outstanding challenge")
public record ResendRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UUID challengeId) {
}
