package com.nightgals.auth;

import com.nightgals.auth.dto.AuthResponse;
import com.nightgals.auth.dto.ForgotPasswordRequest;
import com.nightgals.auth.dto.GoogleLoginRequest;
import com.nightgals.auth.dto.LoginRequest;
import com.nightgals.auth.dto.LoginResponse;
import com.nightgals.auth.dto.OtpChallengeResponse;
import com.nightgals.auth.dto.OtpVerifyRequest;
import com.nightgals.auth.dto.RefreshRequest;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.auth.dto.RegisterResponse;
import com.nightgals.auth.dto.ResetPasswordRequest;
import com.nightgals.auth.google.GoogleIdentity;
import com.nightgals.auth.google.GoogleTokenVerifier;
import com.nightgals.auth.otp.OtpPurpose;
import com.nightgals.auth.otp.OtpService;
import com.nightgals.common.ApiException;
import com.nightgals.common.Hashing;
import com.nightgals.config.JwtProperties;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.mail.EmailService;
import com.nightgals.profile.ProfileRepository;
import com.nightgals.referral.ReferralService;
import com.nightgals.user.AccountType;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.UsernameService;
import com.nightgals.user.UserStatus;
import com.nightgals.user.VerificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final UsernameService usernameService;
    private final OtpService otpService;
    private final EmailService emailService;
    private final ReferralService referralService;
    private final MonetizationProperties monetization;
    private final GoogleTokenVerifier googleTokenVerifier;

    /**
     * Creates the account and signs it in immediately.
     *
     * <p>Nothing is gated on the confirmation email. Somebody who arrived to look
     * at one creator should be looking at her seconds after submitting the form,
     * not sitting in an inbox - so the code is sent, and the account works whether
     * or not it ever arrives.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request, String ipAddress) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        // Not a dead end. Whichever type they picked here, the account they already
        // have can simply become that type once they have proved it is theirs -
        // so the message points at signing in rather than at choosing a different
        // address. Registering a second time is the one thing that would cost
        // them their unlocks and their history.
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("email_taken",
                    "You already have an account with this email. Sign in, and you can "
                            + "switch it between watching and creating whenever you like.");
        }

        // Both are resolved before the row is written: the trial because it is
        // measured from the account existing, the referrer because it can never be
        // set again afterwards.
        Instant trialEnds = monetization.trialEnabled()
                ? Instant.now().plus(monetization.freeTrial()) : null;
        User referrer = referralService.resolve(request.referralCode()).orElse(null);

        User user = userRepository.save(User.builder()
                .email(email)
                .accountType(request.accountType() == null ? AccountType.VIEWER : request.accountType())
                .referralCode(referralService.generateUniqueCode())
                .referredBy(referrer)
                .trialEndsAt(trialEnds)
                // Every account gets a pseudonym up front, so nobody is ever
                // identified to other members by their real name.
                .username(usernameService.generateUnique())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .verificationStatus(VerificationStatus.UNVERIFIED)
                .build());

        log.info("Registered {} account {}{}", user.getAccountType(), user.getId(),
                referrer == null ? "" : " (referred by " + referrer.getId() + ")");

        // Quietly: a mail outage must not cost us a signup. Null comes back when
        // the code could not be sent, and the address simply stays unconfirmed
        // until one is requested again.
        //
        // Swallowing this any further up would not work - OtpService joins this
        // transaction, so an exception crossing its proxy marks the whole thing
        // rollback-only however diligently we catch it here.
        var challenge = otpService.issueQuietly(user, OtpPurpose.EMAIL_VERIFICATION, ipAddress);

        return new RegisterResponse(
                issueTokens(user),
                challenge == null ? null : OtpChallengeResponse.of(challenge));
    }

    /**
     * Checks the password, and hands off to the inbox when the inbox has not been
     * proven yet.
     *
     * <p>By default that is once per account: the first sign-in earns a challenge
     * rather than a session, and answering it records {@code email_verified}, after
     * which the password is enough on its own. Somebody who confirmed their address
     * at registration has already done the proving and is never asked again.
     *
     * <p>The trade is deliberate and configurable. With
     * {@code nightgals.otp.login-first-time-only} off, every sign-in needs a code
     * and a stolen password is worth nothing without the mailbox; with it on, a
     * stolen password is worth a session. {@link OtpService#loginCodeRequiredFor}
     * owns that decision.
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress) {
        User user = userRepository.findByEmailIgnoreCase(request.email().trim())
                // Same message whether the email is unknown or the password is
                // wrong, so the endpoint cannot be used to enumerate accounts.
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));

        // A Google account has no hash to compare against. Same message as a
        // wrong password, deliberately: saying "this one uses Google" would
        // confirm the address is registered to anyone who asks.
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        requireSignInAllowed(user);

        if (!otpService.loginCodeRequiredFor(user)) {
            user.setLastLoginAt(Instant.now());
            return LoginResponse.signedIn(issueTokens(user));
        }

        var challenge = otpService.issue(user, OtpPurpose.LOGIN, ipAddress);
        return LoginResponse.challenge(challenge.challengeId(), challenge.expiresAt(),
                challenge.maskedEmail(), challenge.codeLength());
    }

    /** Exchanges a correct sign-in code for tokens. */
    @Transactional
    public AuthResponse verifyLoginCode(OtpVerifyRequest request) {
        User user = otpService.consume(request.challengeId(), request.code(), OtpPurpose.LOGIN);
        // Re-checked here as well as in login(): an account can be suspended in
        // the minutes between asking for a code and typing it in.
        requireSignInAllowed(user);

        user.setLastLoginAt(Instant.now());
        // Reading a code out of the inbox is proof of control of that inbox,
        // which is all a separate confirmation step was ever asking for.
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            log.info("Address on account {} confirmed by a sign-in code", user.getId());
        }
        return issueTokens(user);
    }

    /**
     * Signs in with Google, creating a viewer account the first time.
     *
     * <p>No code is emailed here and none is needed: the whole purpose of the
     * confirmation code is proving that whoever is signing in reads that inbox,
     * and a Google token minted for this application proves exactly that. Asking
     * anyway would be asking the same question twice.
     *
     * <p><b>This is the viewers' door.</b> A creator account holds identity
     * documents and a payout balance, and its sign-in is two steps on purpose -
     * letting a Google token stand in for both would quietly remove the second
     * for the accounts that can least afford it. So a creator who has a password
     * is sent back to it.
     *
     * <p>The exception is a creator who has no password at all: somebody who
     * signed up here as a viewer and later called {@code /me/become-creator}.
     * Google is the only door they have ever had, and refusing it would not make
     * them safer - it would lock them out of their own account.
     */
    @Transactional
    public AuthResponse googleLogin(GoogleLoginRequest request) {
        GoogleIdentity identity = googleTokenVerifier.verify(request.idToken());
        if (!identity.emailVerified()) {
            throw ApiException.unauthorized(
                    "That Google account's email address is not confirmed with Google.");
        }

        // Subject first, address second. Somebody who changed the address on
        // their Google account is still the same person, and matching on email
        // alone would quietly open them a second account.
        User user = userRepository.findByGoogleSubject(identity.subject())
                .or(() -> userRepository.findByEmailIgnoreCase(identity.email()))
                .orElse(null);

        if (user == null) {
            return issueTokens(createGoogleViewer(identity, request.referralCode()));
        }

        if (user.isCreator() && user.getPasswordHash() != null) {
            throw ApiException.forbidden("creator_password_required",
                    "Creator accounts sign in with an email address and password.");
        }
        requireSignInAllowed(user);

        // First Google sign-in on an account that was registered with a
        // password: link it, so a later address change on the Google side still
        // finds this account.
        if (user.getGoogleSubject() == null) {
            user.setGoogleSubject(identity.subject());
            log.info("Linked Google to existing account {}", user.getId());
        }
        // The address on file is deliberately left alone when it no longer
        // matches Google's. Addresses are unique here, so copying one across
        // can collide with somebody else's account - and the subject we matched
        // on is the better identifier anyway. Changing it is an account
        // settings decision, not a side effect of signing in.
        if (!user.getEmail().equalsIgnoreCase(identity.email())) {
            log.info("Account {} signs in with Google under a different address now", user.getId());
        }
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
        }
        user.setLastLoginAt(Instant.now());
        return issueTokens(user);
    }

    /** Confirms a new account's address. */
    @Transactional
    public void verifyEmail(OtpVerifyRequest request) {
        User user = otpService.consume(
                request.challengeId(), request.code(), OtpPurpose.EMAIL_VERIFICATION);
        boolean firstTime = !user.isEmailVerified();
        user.setEmailVerified(true);

        if (firstTime) {
            emailService.sendWelcome(user.getEmail(), user.getUsername(), user.isCreator());
        }
    }

    /** Sends a fresh code for an outstanding challenge of either kind. */
    @Transactional
    public OtpChallengeResponse resendCode(UUID challengeId) {
        return OtpChallengeResponse.of(otpService.resend(challengeId));
    }

    /** Opens a new confirmation challenge for a signed-in user who never finished. */
    @Transactional
    public OtpChallengeResponse requestEmailVerification(User user, String ipAddress) {
        if (user.isEmailVerified()) {
            throw ApiException.conflict("already_verified", "This address is already confirmed");
        }
        return OtpChallengeResponse.of(
                otpService.issue(user, OtpPurpose.EMAIL_VERIFICATION, ipAddress));
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(Hashing.sha256(request.refreshToken()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));

        if (!stored.isUsable()) {
            throw ApiException.unauthorized("Refresh token has expired or been revoked");
        }

        // Rotate: the presented token dies with the response that replaces it,
        // so a stolen token is usable at most once.
        stored.setRevokedAt(Instant.now());
        return issueTokens(stored.getUser());
    }

    @Transactional
    public void logout(RefreshRequest request) {
        refreshTokenRepository.findByTokenHash(Hashing.sha256(request.refreshToken()))
                .filter(RefreshToken::isUsable)
                .ifPresent(token -> token.setRevokedAt(Instant.now()));
    }

    @Transactional
    public void logoutEverywhere(User user) {
        int revoked = refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
        log.info("Revoked {} refresh tokens for user {}", revoked, user.getId());
    }

    /* ---------------------------------------------------------- password reset */

    /**
     * Opens a recovery challenge and emails the code.
     *
     * <p>An address with no account gets the same response as one that has,
     * complete with a challenge id - it simply answers to nothing. Anything else
     * turns this into a way of asking whether a given person is a member here,
     * which on this platform is not a harmless question to be able to answer.
     *
     * <p>A suspended or closed account is treated the same way, for the same
     * reason: no code is sent, and the caller cannot tell that apart from an
     * address nobody has ever registered.
     */
    @Transactional
    public OtpChallengeResponse requestPasswordReset(ForgotPasswordRequest request, String ipAddress) {
        String email = request.email().trim();
        User user = userRepository.findByEmailIgnoreCase(email)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElse(null);

        if (user == null) {
            log.info("Password reset asked for an address with no usable account");
            return OtpChallengeResponse.of(otpService.decoy(email));
        }

        // Quietly, unlike a sign-in code: this endpoint answers identically for an
        // address that has no account, and a mail outage that turned into a 503
        // here would say "this one exists" for every address that does.
        var challenge = otpService.issueQuietly(user, OtpPurpose.PASSWORD_RESET, ipAddress);
        if (challenge == null) {
            log.warn("Could not email a reset code to user {}; returning a decoy", user.getId());
            return OtpChallengeResponse.of(otpService.decoy(email));
        }
        return OtpChallengeResponse.of(challenge);
    }

    /**
     * Sets a new password against a correct recovery code, and signs the account in.
     *
     * <p>Every existing session is revoked first. Recovery exists for accounts that
     * may already be in somebody else's hands, so leaving the intruder's refresh
     * token working would make the reset nearly pointless - the new tokens in the
     * response are issued after the sweep, so the person who just proved the inbox
     * is the only one still signed in.
     */
    @Transactional
    public AuthResponse resetPassword(ResetPasswordRequest request) {
        User user = otpService.consume(
                request.challengeId(), request.code(), OtpPurpose.PASSWORD_RESET);
        requireSignInAllowed(user);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // Reading the code out of the inbox proves the address, exactly as a
        // sign-in code does - and this account is about to stop being asked for
        // codes at sign-in on the strength of it.
        user.setEmailVerified(true);
        user.setLastLoginAt(Instant.now());

        int revoked = refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
        log.info("Password reset for user {}; revoked {} existing sessions", user.getId(), revoked);

        // The alarm for a reset that was not theirs. Asynchronous and swallowed,
        // so a mail outage cannot undo a password change that has already happened.
        emailService.sendPasswordChanged(user.getEmail(), user.getUsername());

        return issueTokens(user);
    }

    // ------------------------------------------------------------ internals

    /**
     * The Google half of {@link #register}, minus everything a viewer never needs.
     *
     * <p>Always a viewer. Creating is the one moment the account type is decided,
     * and Google is not where somebody chooses to become a creator - that is a
     * deliberate step through {@code /me/become-creator}, with a profile and
     * identity documents behind it.
     */
    private User createGoogleViewer(GoogleIdentity identity, String referralCode) {
        Instant trialEnds = monetization.trialEnabled()
                ? Instant.now().plus(monetization.freeTrial()) : null;
        User referrer = referralService.resolve(referralCode).orElse(null);

        User user = userRepository.save(User.builder()
                .email(identity.email())
                .accountType(AccountType.VIEWER)
                .googleSubject(identity.subject())
                .referralCode(referralService.generateUniqueCode())
                .referredBy(referrer)
                .trialEndsAt(trialEnds)
                .username(usernameService.generateUnique())
                // No password was ever chosen and none is invented. See the
                // column's own note on User - a random hash here would be a
                // credential nobody can use and everybody has to reason about.
                .passwordHash(null)
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .verificationStatus(VerificationStatus.UNVERIFIED)
                // Google already proved control of the inbox.
                .emailVerified(true)
                .lastLoginAt(Instant.now())
                .build());

        log.info("Registered VIEWER account {} through Google{}", user.getId(),
                referrer == null ? "" : " (referred by " + referrer.getId() + ")");

        // The password path sends this when the confirmation code comes back.
        // There is no such step here, so this is the only chance - and it is
        // sent quietly, because a mail outage must not cost us a signup.
        emailService.sendWelcome(user.getEmail(), user.getUsername(), false);
        return user;
    }

    private void requireSignInAllowed(User user) {
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw ApiException.forbidden("account_suspended",
                    "This account has been suspended. Contact support.");
        }
        if (user.getStatus() == UserStatus.DEACTIVATED) {
            throw ApiException.forbidden("account_deactivated", "This account has been closed");
        }
    }

    private AuthResponse issueTokens(User user) {
        String refreshToken = generateOpaqueToken();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(Hashing.sha256(refreshToken))
                .expiresAt(Instant.now().plus(jwtProperties.refreshTokenTtl()))
                .build());

        return new AuthResponse(
                jwtService.issueAccessToken(user),
                refreshToken,
                "Bearer",
                jwtService.accessTokenTtlSeconds(),
                user.getId(),
                user.getUsername(),
                user.getAccountType(),
                user.getRole(),
                user.getVerificationStatus(),
                user.isEmailVerified(),
                user.getTrialEndsAt(),
                profileRepository.isCompleteForUser(user.getId()));
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
