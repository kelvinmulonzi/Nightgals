package com.nightgals.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Sending one gift from the balance")
public record SendGiftRequest(

        @Schema(description = "A code from GET /api/v1/live/gifts", example = "ROSE")
        @NotBlank(message = "Choose a gift")
        String giftCode,

        @Schema(description = "Optional note shown beside the gift", example = "keep going!")
        @Size(max = 200, message = "Keep it under 200 characters")
        String message) {
}
