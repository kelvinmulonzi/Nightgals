package com.nightgals.user;

import com.nightgals.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    @Column(nullable = false, length = 254)
    private String email;

    /**
     * The member's public handle. Other members only ever see this - never the
     * legal name recorded during identity verification.
     */
    @Column(nullable = false, length = 30)
    private String username;

    @Column(name = "username_changed_at")
    private Instant usernameChangedAt;

    /**
     * Null for an account that arrived through Google and never chose one.
     *
     * <p>Every read of this has to cope with that: {@code AuthService.login}
     * refuses a null hash outright rather than handing it to bcrypt, which
     * keeps password sign-in impossible on an account that has no password.
     */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /**
     * Google's stable identifier for this person, set when they first sign in
     * with Google. Null for everybody who registered with a password.
     *
     * <p>Matched on before the email address is: somebody who changes the
     * address on their Google account is still the same person, and looking
     * them up by email alone would open them a second account.
     */
    @Column(name = "google_subject", length = 64)
    private String googleSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 10)
    @Builder.Default
    private AccountType accountType = AccountType.VIEWER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    @Builder.Default
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    /**
     * When a reviewer approved this account's identity documents.
     *
     * <p>What the verified badge is drawn from, and deliberately not
     * {@link #verificationStatus}. That field is the publishing gate, and while
     * identity checks are switched off it is granted automatically the moment a
     * profile is saved - so a badge reading it would tell viewers a document was
     * checked when none was ever uploaded.
     *
     * <p>Null means no badge. Only {@code KycReviewService} sets it.
     */
    @Column(name = "identity_verified_at")
    private Instant identityVerifiedAt;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * When this account's 7 days of free access run out.
     *
     * <p>An expiry rather than a flag, so nothing has to end it: every check is
     * a comparison against now, and a trial that has elapsed simply stops
     * satisfying them.
     */
    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    /** Public, shareable, and fixed for the life of the account. */
    @Column(name = "referral_code", nullable = false, length = 12)
    private String referralCode;

    /**
     * Who invited them. Set once at registration and never moved - letting it
     * change later would let two people claim the same bonus.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_by")
    private User referredBy;

    /**
     * Guarantees the invite code the NOT NULL column requires.
     *
     * <p>{@link com.nightgals.referral.ReferralService} generates a checked-unique
     * one during registration. This is the backstop for every other path that
     * creates an account - the admin bootstrap, tests, a future import - none of
     * which should have to remember.
     */
    @PrePersist
    void ensureReferralCode() {
        if (referralCode == null || referralCode.isBlank()) {
            referralCode = com.nightgals.referral.ReferralCodes.random();
        }
    }

    /** May publish and be discovered. Not what the badge means - see below. */
    public boolean isApproved() {
        return verificationStatus == VerificationStatus.APPROVED;
    }

    /**
     * A human checked this person's identity documents.
     *
     * <p>What the verified badge asks. Narrower than {@link #isApproved()}: an
     * account can publish without ever having uploaded a document, and must not
     * wear a badge saying otherwise.
     */
    public boolean isIdentityVerified() {
        return identityVerifiedAt != null;
    }

    public boolean isStaff() {
        return role == Role.ADMIN || role == Role.MODERATOR;
    }

    public boolean isCreator() {
        return accountType == AccountType.CREATOR;
    }

    /** True while the free trial is still running. */
    public boolean isOnTrial() {
        return trialEndsAt != null && trialEndsAt.isAfter(Instant.now());
    }

}
