package com.nightgals.auth;

import com.nightgals.auth.dto.AuthResponse;
import com.nightgals.auth.dto.LoginRequest;
import com.nightgals.auth.dto.LoginResponse;
import com.nightgals.auth.dto.OtpChallengeResponse;
import com.nightgals.auth.dto.OtpVerifyRequest;
import com.nightgals.auth.dto.RefreshRequest;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.auth.dto.RegisterResponse;
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
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("email_taken", "An account with this email already exists");
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
     * Checks the password, then hands off to the inbox.
     *
     * <p>No tokens are issued here while codes are on: the password earns a
     * challenge, and only the emailed code earns a session.
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress) {
        User user = userRepository.findByEmailIgnoreCase(request.email().trim())
                // Same message whether the email is unknown or the password is
                // wrong, so the endpoint cannot be used to enumerate accounts.
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        requireSignInAllowed(user);

        if (!otpService.loginCodeRequired()) {
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

    // ------------------------------------------------------------ internals

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
                profileRepository.existsByUserId(user.getId()));
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
