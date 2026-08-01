package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.auth.otp.OtpChallengeRepository;
import com.nightgals.common.ApiException;
import com.nightgals.mail.EmailService;
import com.nightgals.user.AccountType;
import com.nightgals.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;

/**
 * Registration survives a dead mail server.
 *
 * <p>Regression test for a real failure: the SMTP credentials were rejected, the
 * confirmation email threw, {@code AuthService.register} caught it and carried
 * on - and the request still died with {@code UnexpectedRollbackException}.
 *
 * <p>The catch was in the wrong place. {@code OtpService} is {@code @Transactional}
 * and joins the caller's transaction, so an exception crossing <em>its</em> proxy
 * marks the shared transaction rollback-only. By the time the caller catches
 * anything the transaction is already doomed, and the failure only surfaces at
 * commit, after the handler has returned a perfectly good response.
 *
 * <p>Deliberately NOT {@code @Transactional}: the bug lives in the commit, so a
 * test that never commits cannot see it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RegistrationWithoutMailTest {

    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired OtpChallengeRepository challengeRepository;

    @MockitoBean EmailService emailService;

    @Test
    @DisplayName("A signup is not lost when the confirmation email cannot be sent")
    void registrationSurvivesMailFailure() {
        // Exactly what a rejected Gmail app password produces.
        willThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "email_send_failed",
                "We could not send the code to your email. Try again in a moment."))
                .given(emailService).sendVerificationCode(anyString(), anyString(), anyString(), any());

        String email = "no-mail-" + UUID.randomUUID() + "@example.com";

        // Before the fix this threw UnexpectedRollbackException at commit.
        assertThatCode(() -> authService.register(
                new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null))
                .doesNotThrowAnyException();

        // The account is real, committed, and usable.
        var registered = userRepository.findByEmailIgnoreCase(email);
        assertThat(registered).isPresent();
        assertThat(registered.get().isEmailVerified()).isFalse();

        // And the response says there is nothing to confirm, rather than handing
        // the client a challenge for a code that will never arrive.
        var second = "second-" + email;
        var response = authService.register(
                new RegisterRequest(second, "correct-horse-9", AccountType.VIEWER, null), null);
        assertThat(response.auth().accessToken()).isNotBlank();
        assertThat(response.emailVerification()).isNull();

        cleanUp(email);
        cleanUp(second);
    }

    @Test
    @DisplayName("The unanswerable challenge is burned rather than left outstanding")
    void undeliverableChallengeIsBurned() {
        willThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "email_send_failed", "nope"))
                .given(emailService).sendVerificationCode(anyString(), anyString(), anyString(), any());

        String email = "burned-" + UUID.randomUUID() + "@example.com";
        var registered = authService.register(
                new RegisterRequest(email, "correct-horse-9", AccountType.VIEWER, null), null);

        var user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        var stranded = challengeRepository.findAll().stream()
                .filter(c -> c.getUser().getId().equals(user.getId()))
                .toList();

        // A row may exist, but none of it is live: an outstanding challenge would
        // also block the next code under the one-live-code-per-purpose rule.
        assertThat(stranded).allSatisfy(c -> assertThat(c.isUsable()).isFalse());

        cleanUp(email);
    }

    /** No @Transactional here, so rows survive the test and have to be removed. */
    private void cleanUp(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
    }
}
