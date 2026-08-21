package com.nightgals.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "The emailed recovery code, and the password to replace the old one with")
public record ResetPasswordRequest(

        @Schema(description = "From the /auth/password/forgot response",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID challengeId,

        @Schema(example = "418902", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 12) String code,

        // The same rules registration enforces. A recovery flow that accepted a
        // weaker password than signup would be the easiest way to get one.
        @Schema(description = "At least 10 characters, with a letter and a digit",
                example = "correct-horse-9", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 10, max = 72, message = "Password must be between 10 and 72 characters")
        @Pattern(regexp = ".*[A-Za-z].*", message = "Password must contain a letter")
        @Pattern(regexp = ".*\\d.*", message = "Password must contain a digit")
        String newPassword) {
}
