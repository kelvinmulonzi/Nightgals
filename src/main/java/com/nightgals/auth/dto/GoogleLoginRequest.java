package com.nightgals.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "A Google ID token, straight from Google")
public record GoogleLoginRequest(

        @Schema(description = """
                The `credential` field of the response Google hands the browser -
                a signed JWT, not an access token and not an authorisation code.

                The server verifies its signature, its expiry, and that its audience
                is this application's own client id before it means anything.
                """, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 10, max = 4096)
        String idToken,

        @Schema(description = """
                An invite code, if they arrived through somebody's referral link.

                Only applied when this call actually creates the account - somebody
                who already has one keeps whoever first sent them. A code matching
                nothing is ignored rather than rejected.
                """, example = "K7RBQ2XM")
        @Size(max = 12)
        String referralCode) {
}
