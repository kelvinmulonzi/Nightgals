package com.nightgals.auth.dto;

import com.nightgals.user.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "New account details")
public record RegisterRequest(

        @Schema(example = "amina@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email @Size(max = 254)
        String email,

        @Schema(description = "At least 10 characters, with a letter and a digit",
                example = "correct-horse-9", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 10, max = 72, message = "Password must be between 10 and 72 characters")
        @Pattern(regexp = ".*[A-Za-z].*", message = "Password must contain a letter")
        @Pattern(regexp = ".*\\d.*", message = "Password must contain a digit")
        String password,

        @Schema(description = """
                What this account is for. Defaults to `VIEWER`.

                `VIEWER` - browse and pay for content. Nothing else is ever asked for:
                no profile, no date of birth, no identity documents.

                `CREATOR` - post content and earn from it. Requires a profile and
                identity verification before anything can be published.

                A viewer can switch later with `POST /api/v1/me/become-creator`.
                """)
        AccountType accountType,

        @Schema(description = """
                An invite code, if they arrived through somebody's referral link.

                Optional, and a code that matches nothing is ignored rather than
                rejected - somebody mistyping an invite should still end up with an
                account. The referrer is credited when this account buys its first
                package, not now.
                """, example = "K7RBQ2XM")
        @Size(max = 12)
        String referralCode) {
}
