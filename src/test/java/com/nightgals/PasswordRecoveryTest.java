package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.RefreshTokenRepository;
import com.nightgals.auth.dto.ForgotPasswordRequest;
import com.nightgals.auth.dto.LoginRequest;
import com.nightgals.auth.dto.OtpVerifyRequest;
import com.nightgals.auth.dto.RefreshRequest;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.auth.dto.ResetPasswordRequest;
import com.nightgals.auth.otp.OtpChallengeRepository;
import com.nightgals.auth.otp.OtpPurpose;
import com.nightgals.common.ApiException;
import com.nightgals.common.Hashing;
import com.nightgals.user.AccountType;
import com.nightgals.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Getting back in without the password, and only being asked for a code once.
 *
 * <p>Codes are stored hashed, so these tests recover one by hashing the whole
 * six-digit space against the stored value - the same trick {@code LoginOtpTest}
 * uses, and only possible because the space is small.
 *
 * <p>Mail is disabled in the test profile, so nothing leaves the process.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.otp.login-required=true",
        "nightgals.otp.login-first-time-only=true",
})
@Transactional
class PasswordRecoveryTest {

    private static final String PASSWORD = "correct-horse-9";
    private static final String NEW_PASSWORD = "battery-staple-4";

    @Autowired AuthService authService;
    @Autowired OtpChallengeRepository challengeRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired UserRepository userRepository;

    // ------------------------------------------------------- one code, once

    @Test
    @DisplayName("The first sign-in needs a code; the ones after it do not")
    void codeIsAskedForOnceOnly() {
        String email = register();

        var first = authService.login(new LoginRequest(email, PASSWORD), null);
        assertThat(first.otpRequired()).isTrue();
        assertThat(first.auth()).isNull();

        authService.verifyLoginCode(
                new OtpVerifyRequest(first.challengeId(), crackCode(first.challengeId())));

        var second = authService.login(new LoginRequest(email, PASSWORD), null);
        assertThat(second.otpRequired()).isFalse();
        assertThat(second.challengeId()).isNull();
        assertThat(second.auth().accessToken()).isNotBlank();

        // Third time too - this is a property of the account, not a one-off skip.
        assertThat(authService.login(new LoginRequest(email, PASSWORD), null).otpRequired())
                .isFalse();
    }

    @Test
    @DisplayName("Confirming the address at registration also settles the sign-in code")
    void confirmingAtRegistrationCountsAsTheInboxCheck() {
        String email = email();
        var registered = authService.register(
                new RegisterRequest(email, PASSWORD, AccountType.VIEWER, null), null);
        UUID challengeId = registered.emailVerification().challengeId();

        authService.verifyEmail(new OtpVerifyRequest(challengeId, crackCode(challengeId)));

        // The inbox has already been proven, so the very first sign-in skips it.
        assertThat(authService.login(new LoginRequest(email, PASSWORD), null).otpRequired())
                .isFalse();
    }

    @Test
    @DisplayName("A wrong password is still refused once codes have stopped")
    void passwordIsStillCheckedAfterTheFirstSignIn() {
        String email = registeredAndVerified();

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "not-the-password-1"), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid email or password");
    }

    // ------------------------------------------------------ forgotten password

    @Test
    @DisplayName("A recovery code replaces the password and signs the account in")
    void resetChangesThePassword() {
        String email = registeredAndVerified();

        var challenge = authService.requestPasswordReset(new ForgotPasswordRequest(email), null);
        assertThat(challengeRepository.findById(challenge.challengeId()).orElseThrow().getPurpose())
                .isEqualTo(OtpPurpose.PASSWORD_RESET);

        var auth = authService.resetPassword(new ResetPasswordRequest(
                challenge.challengeId(), crackCode(challenge.challengeId()), NEW_PASSWORD));

        assertThat(auth.accessToken()).isNotBlank();
        assertThat(auth.refreshToken()).isNotBlank();

        // The old one is dead and the new one works.
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, PASSWORD), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid email or password");
        assertThat(authService.login(new LoginRequest(email, NEW_PASSWORD), null).auth())
                .isNotNull();
    }

    @Test
    @DisplayName("Resetting signs out everything that was already signed in")
    void resetRevokesExistingSessions() {
        String email = registeredAndVerified();
        String stolenRefreshToken =
                authService.login(new LoginRequest(email, PASSWORD), null).auth().refreshToken();
        // It works right up until the reset.
        assertThat(authService.refresh(new RefreshRequest(stolenRefreshToken)).accessToken())
                .isNotBlank();

        var challenge = authService.requestPasswordReset(new ForgotPasswordRequest(email), null);
        var auth = authService.resetPassword(new ResetPasswordRequest(
                challenge.challengeId(), crackCode(challenge.challengeId()), NEW_PASSWORD));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(stolenRefreshToken)))
                .isInstanceOf(ApiException.class);
        // The session handed back by the reset is the one that survives.
        assertThat(authService.refresh(new RefreshRequest(auth.refreshToken())).accessToken())
                .isNotBlank();
    }

    @Test
    @DisplayName("Recovery also counts as proving the inbox")
    void resetSettlesTheInboxCheck() {
        String email = email();
        authService.register(new RegisterRequest(email, PASSWORD, AccountType.VIEWER, null), null);

        var challenge = authService.requestPasswordReset(new ForgotPasswordRequest(email), null);
        authService.resetPassword(new ResetPasswordRequest(
                challenge.challengeId(), crackCode(challenge.challengeId()), NEW_PASSWORD));

        assertThat(userRepository.findByEmailIgnoreCase(email).orElseThrow().isEmailVerified())
                .isTrue();
        assertThat(authService.login(new LoginRequest(email, NEW_PASSWORD), null).otpRequired())
                .isFalse();
    }

    @Test
    @DisplayName("A wrong code cannot change anybody's password")
    void wrongCodeChangesNothing() {
        String email = registeredAndVerified();
        var challenge = authService.requestPasswordReset(new ForgotPasswordRequest(email), null);

        assertThatThrownBy(() -> authService.resetPassword(new ResetPasswordRequest(
                challenge.challengeId(), wrongCodeFor(challenge.challengeId()), NEW_PASSWORD)))
                .isInstanceOf(ApiException.class);

        assertThat(authService.login(new LoginRequest(email, PASSWORD), null).auth()).isNotNull();
    }

    @Test
    @DisplayName("A sign-in code cannot be spent as a recovery code")
    void purposesDoNotCross() {
        String email = register();
        var login = authService.login(new LoginRequest(email, PASSWORD), null);
        String code = crackCode(login.challengeId());

        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest(login.challengeId(), code, NEW_PASSWORD)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no longer valid");
    }

    // --------------------------------------------------------- no enumeration

    @Test
    @DisplayName("An address with no account gets a challenge that answers to nothing")
    void unknownAddressIsIndistinguishable() {
        long before = challengeRepository.count();

        var challenge = authService.requestPasswordReset(
                new ForgotPasswordRequest("nobody-" + UUID.randomUUID() + "@example.com"), null);

        // Shaped exactly like the real thing, so the client cannot tell them apart.
        assertThat(challenge.challengeId()).isNotNull();
        assertThat(challenge.maskedEmail()).contains("•").contains("@example.com");
        assertThat(challenge.codeLength()).isEqualTo(6);
        // But nothing was written, so it can never be answered.
        assertThat(challengeRepository.count()).isEqualTo(before);
        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest(challenge.challengeId(), "000000", NEW_PASSWORD)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no longer valid");
    }

    // ------------------------------------------------------------- helpers

    private String crackCode(UUID challengeId) {
        String hash = challengeRepository.findById(challengeId).orElseThrow().getCodeHash();
        for (int i = 0; i < 1_000_000; i++) {
            String candidate = String.format("%06d", i);
            if (Hashing.sha256(candidate).equals(hash)) {
                return candidate;
            }
        }
        throw new AssertionError("No six-digit code matched the stored hash");
    }

    private String wrongCodeFor(UUID challengeId) {
        return crackCode(challengeId).equals("000000") ? "111111" : "000000";
    }

    /** A fresh account that has never proved its inbox. */
    private String register() {
        String email = email();
        authService.register(new RegisterRequest(email, PASSWORD, AccountType.VIEWER, null), null);
        return email;
    }

    /** A fresh account that has been through its one sign-in code already. */
    private String registeredAndVerified() {
        String email = register();
        var challenge = authService.login(new LoginRequest(email, PASSWORD), null);
        authService.verifyLoginCode(
                new OtpVerifyRequest(challenge.challengeId(), crackCode(challenge.challengeId())));
        return email;
    }

    private String email() {
        return "reset-" + UUID.randomUUID() + "@example.com";
    }
}
