package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.LoginRequest;
import com.nightgals.auth.dto.OtpVerifyRequest;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.auth.otp.OtpChallenge;
import com.nightgals.auth.otp.OtpChallengeRepository;
import com.nightgals.auth.otp.OtpPurpose;
import com.nightgals.auth.otp.OtpService;
import com.nightgals.common.ApiException;
import com.nightgals.common.Hashing;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.UserStatus;
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
 * A password gets you a code, and only the code gets you a session.
 *
 * <p>Codes are only ever stored hashed, so the tests cannot read one back. They
 * brute-force the six-digit space against the stored hash instead - a thousandth
 * of a second here, and the reason a real attacker gets five guesses rather than
 * a million.
 *
 * <p>Mail is disabled in the test profile, so nothing leaves the process.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "nightgals.otp.login-required=true")
@Transactional
class LoginOtpTest {

    private static final String PASSWORD = "correct-horse-9";

    @Autowired AuthService authService;
    @Autowired OtpService otpService;
    @Autowired OtpChallengeRepository challengeRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("A correct password returns a challenge, not a session")
    void passwordAloneIsNotEnough() {
        String email = register();

        var response = authService.login(new LoginRequest(email, PASSWORD), "127.0.0.1");

        assertThat(response.otpRequired()).isTrue();
        assertThat(response.challengeId()).isNotNull();
        // No tokens anywhere in the response - that is the whole point.
        assertThat(response.auth()).isNull();
        // Enough to know which inbox to open, not enough to learn the address.
        assertThat(response.maskedEmail()).contains("•").contains("@example.com");
        assertThat(response.maskedEmail()).isNotEqualTo(email);
    }

    @Test
    @DisplayName("The emailed code exchanges for tokens")
    void correctCodeSignsIn() {
        String email = register();
        var challenge = authService.login(new LoginRequest(email, PASSWORD), null);
        String code = crackCode(challenge.challengeId());

        var auth = authService.verifyLoginCode(new OtpVerifyRequest(challenge.challengeId(), code));

        assertThat(auth.accessToken()).isNotBlank();
        assertThat(auth.refreshToken()).isNotBlank();
        // Reading the code out of the inbox is proof of controlling it.
        assertThat(auth.emailVerified()).isTrue();
    }

    @Test
    @DisplayName("A code cannot be used twice")
    void codeIsSingleUse() {
        String email = register();
        var challenge = authService.login(new LoginRequest(email, PASSWORD), null);
        String code = crackCode(challenge.challengeId());

        authService.verifyLoginCode(new OtpVerifyRequest(challenge.challengeId(), code));

        assertThatThrownBy(() -> authService.verifyLoginCode(
                new OtpVerifyRequest(challenge.challengeId(), code)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    @DisplayName("Wrong codes are counted, and the challenge burns out")
    void wrongCodesBurnTheChallenge() {
        String email = register();
        var challenge = authService.login(new LoginRequest(email, PASSWORD), null);
        String wrong = wrongCodeFor(challenge.challengeId());

        // max-attempts is 5 in the test configuration.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.verifyLoginCode(
                    new OtpVerifyRequest(challenge.challengeId(), wrong)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("left");
        }

        assertThatThrownBy(() -> authService.verifyLoginCode(
                new OtpVerifyRequest(challenge.challengeId(), wrong)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many wrong codes");

        // Even the right code is worthless now.
        assertThat(challengeRepository.findById(challenge.challengeId()).orElseThrow().getConsumedAt())
                .isNotNull();
    }

    @Test
    @DisplayName("An unknown challenge id gives nothing away")
    void unknownChallengeIsOpaque() {
        assertThatThrownBy(() -> authService.verifyLoginCode(
                new OtpVerifyRequest(UUID.randomUUID(), "123456")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no longer valid");
    }

    @Test
    @DisplayName("Signing in again retires the previous code")
    void onlyOneLiveCodePerUser() {
        String email = register();
        var first = authService.login(new LoginRequest(email, PASSWORD), null);
        String firstCode = crackCode(first.challengeId());

        var second = authService.login(new LoginRequest(email, PASSWORD), null);

        assertThatThrownBy(() -> authService.verifyLoginCode(
                new OtpVerifyRequest(first.challengeId(), firstCode)))
                .isInstanceOf(ApiException.class);

        // The newest one still works.
        authService.verifyLoginCode(
                new OtpVerifyRequest(second.challengeId(), crackCode(second.challengeId())));
    }

    @Test
    @DisplayName("Resending replaces the code rather than adding one")
    void resendReplacesTheCode() {
        String email = register();
        var challenge = authService.login(new LoginRequest(email, PASSWORD), null);
        String original = crackCode(challenge.challengeId());

        authService.resendCode(challenge.challengeId());

        assertThatThrownBy(() -> authService.verifyLoginCode(
                new OtpVerifyRequest(challenge.challengeId(), original)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not right");

        authService.verifyLoginCode(
                new OtpVerifyRequest(challenge.challengeId(), crackCode(challenge.challengeId())));
    }

    @Test
    @DisplayName("A suspended account gets no code at all")
    void suspendedAccountIsRefusedBeforeAnyCode() {
        String email = register();
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setStatus(UserStatus.SUSPENDED);
        userRepository.saveAndFlush(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, PASSWORD), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");
    }

    @Test
    @DisplayName("A wrong password never reaches the code stage")
    void wrongPasswordStopsEarly() {
        String email = register();
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        // Registration opens an email-confirmation challenge; only LOGIN ones matter here.
        long before = loginChallengesFor(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "not-the-password-1"), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid email or password");

        assertThat(loginChallengesFor(user)).isEqualTo(before).isZero();
    }

    @Test
    @DisplayName("Registration signs the user in without waiting on a code")
    void registrationIsOneStep() {
        var response = authService.register(
                new RegisterRequest(email(), PASSWORD, AccountType.VIEWER, null), null);

        assertThat(response.auth().accessToken()).isNotBlank();
        assertThat(response.auth().accountType()).isEqualTo(AccountType.VIEWER);
        // A confirmation code was opened alongside, but nothing is gated on it.
        assertThat(response.emailVerification()).isNotNull();
        assertThat(response.auth().emailVerified()).isFalse();
    }

    @Test
    @DisplayName("Confirming the address consumes the registration challenge")
    void emailConfirmation() {
        var response = authService.register(
                new RegisterRequest(email(), PASSWORD, AccountType.VIEWER, null), null);
        UUID challengeId = response.emailVerification().challengeId();

        authService.verifyEmail(new OtpVerifyRequest(challengeId, crackCode(challengeId)));

        assertThat(userRepository.findById(response.auth().userId()).orElseThrow().isEmailVerified())
                .isTrue();
    }

    @Test
    @DisplayName("A login code cannot be spent as an email-confirmation code")
    void purposesDoNotCross() {
        String email = register();
        var challenge = authService.login(new LoginRequest(email, PASSWORD), null);
        String code = crackCode(challenge.challengeId());

        assertThatThrownBy(() -> authService.verifyEmail(
                new OtpVerifyRequest(challenge.challengeId(), code)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no longer valid");
    }

    @Test
    @DisplayName("Challenges are stored hashed, never in the clear")
    void codesAreNotStoredInTheClear() {
        String email = register();
        var challenge = authService.login(new LoginRequest(email, PASSWORD), null);

        OtpChallenge stored = challengeRepository.findById(challenge.challengeId()).orElseThrow();
        String code = crackCode(challenge.challengeId());

        assertThat(stored.getCodeHash()).isNotEqualTo(code).hasSize(64);
        assertThat(stored.getPurpose()).isEqualTo(OtpPurpose.LOGIN);
    }

    // ------------------------------------------------------------- helpers

    /**
     * Recovers the plaintext code by hashing every value in the space until one
     * matches. Only possible because the space is small - which is exactly why
     * the attempt limit exists.
     */
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

    /** Any code that is definitely not the live one. */
    private String wrongCodeFor(UUID challengeId) {
        String real = crackCode(challengeId);
        return real.equals("000000") ? "111111" : "000000";
    }

    private long loginChallengesFor(User user) {
        return challengeRepository.findAll().stream()
                .filter(c -> c.getUser().getId().equals(user.getId()))
                .filter(c -> c.getPurpose() == OtpPurpose.LOGIN)
                .count();
    }

    private String register() {
        String email = email();
        authService.register(new RegisterRequest(email, PASSWORD, AccountType.VIEWER, null), null);
        return email;
    }

    private String email() {
        return "otp-" + UUID.randomUUID() + "@example.com";
    }
}
