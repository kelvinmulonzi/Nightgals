package com.nightgals.auth.dto;

import com.nightgals.auth.otp.OtpService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A code has been emailed; answer it at /auth/otp/verify")
public record OtpChallengeResponse(
        UUID challengeId,
        @Schema(description = "When the code stops working") Instant expiresAt,
        @Schema(example = "am••••@example.com") String maskedEmail,
        @Schema(example = "6") int codeLength) {

    public static OtpChallengeResponse of(OtpService.Challenge challenge) {
        return new OtpChallengeResponse(
                challenge.challengeId(),
                challenge.expiresAt(),
                challenge.maskedEmail(),
                challenge.codeLength());
    }
}
