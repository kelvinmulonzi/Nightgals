package com.nightgals.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Claim a specific handle")
public record UsernameChangeRequest(
        @Schema(description = "3-30 characters, starts with a letter, letters/digits/underscores only",
                example = "NairobiNights", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 3, max = 30) String username) {
}
