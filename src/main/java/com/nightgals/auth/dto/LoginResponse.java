package com.nightgals.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * What {@code POST /auth/login} returns.
 *
 * <p>Exactly one of the two halves is populated, and {@code otpRequired} says
 * which. This is a single response type rather than two endpoints because the
 * client cannot know in advance whether codes are switched on.
 */
@Schema(description = """
        Either a challenge to answer, or a signed-in session.

        `otpRequired: true`  - the password was right and a code has been emailed.
                               Send `challengeId` and the code to `/auth/otp/verify`.
        `otpRequired: false` - codes are switched off; `auth` holds the tokens.
        """)
public record LoginResponse(

        @Schema(description = "True when a code has been emailed and must be verified")
        boolean otpRequired,

        @Schema(description = "Pass to /auth/otp/verify with the code. Null when otpRequired is false.")
        UUID challengeId,

        @Schema(description = "When the emailed code stops working")
        Instant expiresAt,

        @Schema(description = "Which inbox the code went to, masked", example = "am••••@example.com")
        String maskedEmail,

        @Schema(description = "How many digits the code has", example = "6")
        Integer codeLength,

        @Schema(description = "Tokens and identity. Only present when otpRequired is false.")
        AuthResponse auth) {

    public static LoginResponse challenge(UUID challengeId, Instant expiresAt,
                                          String maskedEmail, int codeLength) {
        return new LoginResponse(true, challengeId, expiresAt, maskedEmail, codeLength, null);
    }

    public static LoginResponse signedIn(AuthResponse auth) {
        return new LoginResponse(false, null, null, null, null, auth);
    }
}
