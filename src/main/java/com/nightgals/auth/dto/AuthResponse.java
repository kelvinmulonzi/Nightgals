package com.nightgals.auth.dto;

import com.nightgals.user.AccountType;
import com.nightgals.user.Role;
import com.nightgals.user.VerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Issued tokens plus the caller's current standing")
public record AuthResponse(
        @Schema(description = "Send as: Authorization: Bearer <accessToken>")
        String accessToken,
        @Schema(description = "Use with POST /api/v1/auth/refresh. Store securely; it is shown once.")
        String refreshToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Access token lifetime in seconds", example = "1800") long expiresIn,
        UUID userId,
        @Schema(description = "The caller's public handle. Other members see this, never their real name.",
                example = "VelvetFalcon482")
        String username,

        @Schema(description = "VIEWER or CREATOR - decides what the client shows next")
        AccountType accountType,

        Role role,
        @Schema(description = "APPROVED is required before media upload") VerificationStatus verificationStatus,

        @Schema(description = """
                Whether the address on the account has been confirmed. Signing in with
                an emailed code confirms it, so this is true for anyone who has completed
                a code-based sign-in.
                """)
        boolean emailVerified,

        @Schema(description = """
                When the 7 days of free access run out. Everything is open until then -
                a viewer sees premium content, a creator can publish - so a client should
                surface this rather than let it expire as a surprise.
                """)
        java.time.Instant trialEndsAt,

        @Schema(description = "False until the user completes their profile. Creators only.")
        boolean profileComplete) {
}
