package com.nightgals;

import com.nightgals.auth.otp.OtpService;
import com.nightgals.config.OtpProperties;
import com.nightgals.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who gets asked for a sign-in code.
 *
 * <p>Plain JUnit rather than {@code @SpringBootTest}: the decision reads two
 * booleans and one field on the account, and pinning it here costs nothing while
 * a third application context - one per property combination - would.
 */
class LoginCodePolicyTest {

    @Test
    @DisplayName("First time only: the code is asked for until the inbox is proven")
    void firstTimeOnly() {
        OtpService service = otpService(true, true);

        assertThat(service.loginCodeRequiredFor(account(false))).isTrue();
        assertThat(service.loginCodeRequiredFor(account(true))).isFalse();
    }

    @Test
    @DisplayName("Switched off: every sign-in needs a code, proven inbox or not")
    void everyTime() {
        OtpService service = otpService(true, false);

        assertThat(service.loginCodeRequiredFor(account(false))).isTrue();
        assertThat(service.loginCodeRequiredFor(account(true))).isTrue();
    }

    @Test
    @DisplayName("login-required=false wins over everything - break-glass means break-glass")
    void codesOffEntirely() {
        assertThat(otpService(false, false).loginCodeRequiredFor(account(false))).isFalse();
        assertThat(otpService(false, true).loginCodeRequiredFor(account(false))).isFalse();
    }

    /**
     * Only the properties are read by the method under test, so the repository and
     * the mailer are deliberately absent - if that ever stops being true this test
     * fails loudly with an NPE rather than quietly passing.
     */
    private OtpService otpService(boolean loginRequired, boolean firstTimeOnly) {
        return new OtpService(null, new OtpProperties(
                loginRequired, firstTimeOnly, 6, Duration.ofMinutes(10),
                5, 3, Duration.ofSeconds(30), 10, "0 15 4 * * *"), null);
    }

    private User account(boolean emailVerified) {
        User user = new User();
        user.setEmailVerified(emailVerified);
        return user;
    }
}
