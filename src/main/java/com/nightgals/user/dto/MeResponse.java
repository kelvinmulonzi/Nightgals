package com.nightgals.user.dto;

import com.nightgals.user.AccountType;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserStatus;
import com.nightgals.user.VerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "The caller's account, and what it can currently do")
public record MeResponse(
        UUID id,
        String email,

        @Schema(description = "What this account is for. Decides the whole onboarding path.")
        AccountType accountType,

        @Schema(description = "Public handle shown to other members", example = "VelvetFalcon482")
        String username,
        Role role,
        UserStatus status,
        VerificationStatus verificationStatus,
        boolean emailVerified,

        @Schema(description = "True while the 7-day free trial is running")
        boolean onTrial,
        @Schema(description = "When the free trial ends") Instant trialEndsAt,

        @Schema(description = "This account's invite code", example = "K7RBQ2XM")
        String referralCode,

        boolean profileComplete,
        @Schema(description = "True once an administrator has approved this member's identity documents")
        boolean canPostMedia,
        @Schema(description = """
                What the client should show next.

                Viewers are only ever `BROWSE` - there is nothing for them to complete.
                Creators walk `CREATE_PROFILE` -> `SUBMIT_KYC` -> `AWAIT_REVIEW` -> `DONE`,
                with `RESUBMIT_KYC` if a review failed.
                """, example = "BROWSE",
                allowableValues = {"BROWSE", "CREATE_PROFILE", "SUBMIT_KYC", "AWAIT_REVIEW",
                        "RESUBMIT_KYC", "DONE"})
        String nextStep,

        @Schema(description = """
                Whether this deployment asks creators for identity documents.

                When false there is no document step and no review queue: a saved
                profile finishes onboarding, `nextStep` goes straight to `DONE`, and
                the client should not offer or mention verification at all.
                """)
        boolean kycRequired,
        Instant lastLoginAt,
        Instant createdAt) {

    /**
     * For callers with no view of configuration - the tests, chiefly. Assumes
     * identity checks are on, which is the stricter of the two answers.
     */
    public static MeResponse of(User user, boolean profileComplete) {
        return of(user, profileComplete, true);
    }

    public static MeResponse of(User user, boolean profileComplete, boolean kycRequired) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getAccountType(),
                user.getUsername(),
                user.getRole(),
                user.getStatus(),
                user.getVerificationStatus(),
                user.isEmailVerified(),
                user.isOnTrial(),
                user.getTrialEndsAt(),
                user.getReferralCode(),
                profileComplete,
                user.isApproved(),
                nextStep(user, profileComplete, kycRequired),
                kycRequired,
                user.getLastLoginAt(),
                user.getCreatedAt());
    }

    private static String nextStep(User user, boolean profileComplete, boolean kycRequired) {
        // A viewer has nothing to complete. Telling them to create a profile or
        // upload a passport is what made the app feel like it was for somebody else.
        if (!user.isCreator()) {
            return "BROWSE";
        }
        if (!profileComplete) {
            return "CREATE_PROFILE";
        }
        // Nothing to submit and nobody to wait for. Read here as well as at the
        // point of approval so an account that predates the switch is not sent
        // to a document form this deployment no longer has.
        if (!kycRequired) {
            return "DONE";
        }
        return switch (user.getVerificationStatus()) {
            case UNVERIFIED -> "SUBMIT_KYC";
            case PENDING_REVIEW -> "AWAIT_REVIEW";
            case REJECTED -> "RESUBMIT_KYC";
            case APPROVED -> "DONE";
        };
    }
}
