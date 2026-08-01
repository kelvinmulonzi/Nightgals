package com.nightgals.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "The code from the email, plus the challenge it answers")
public record OtpVerifyRequest(

        @Schema(description = "From the login or register response",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID challengeId,

        @Schema(example = "418902", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 12) String code) {
}
