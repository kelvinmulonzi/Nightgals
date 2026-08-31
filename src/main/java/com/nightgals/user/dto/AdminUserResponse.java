package com.nightgals.user.dto;

import com.nightgals.user.AccountType;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserStatus;
import com.nightgals.user.VerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One account as the staff console sees it.
 *
 * <p>Everything a moderator needs to decide whether to burn an account and to
 * understand one that already is - and nothing else. No password hash, no
 * Google subject, no identity documents: those live behind the KYC desk, which
 * logs every look at them.
 */
@Schema(description = "An account, for the staff console")
public record AdminUserResponse(
        UUID id,
        String email,
        String username,
        AccountType accountType,
        Role role,

        @Schema(description = "ACTIVE, SUSPENDED (burned by a moderator), or DEACTIVATED (closed by the member)")
        UserStatus status,

        VerificationStatus verificationStatus,

        @Schema(description = "True while the account is burned: signed out, refused sign-in, and hidden from every public listing")
        boolean suspended,

        Instant suspendedAt,
        String suspendedReason,

        @Schema(description = "Which staff account burned it. Null for accounts burned before this was recorded.")
        UUID suspendedById,

        boolean emailVerified,
        Instant trialEndsAt,
        Instant lastLoginAt,
        Instant createdAt) {

    public static AdminUserResponse of(User u) {
        return new AdminUserResponse(
                u.getId(),
                u.getEmail(),
                u.getUsername(),
                u.getAccountType(),
                u.getRole(),
                u.getStatus(),
                u.getVerificationStatus(),
                u.getStatus() == UserStatus.SUSPENDED,
                u.getSuspendedAt(),
                u.getSuspendedReason(),
                u.getSuspendedById(),
                u.isEmailVerified(),
                u.getTrialEndsAt(),
                u.getLastLoginAt(),
                u.getCreatedAt());
    }
}
