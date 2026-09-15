package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.live.LiveOverrunJob;
import com.nightgals.live.LiveSession;
import com.nightgals.live.LiveSessionRepository;
import com.nightgals.live.LiveSessionService;
import com.nightgals.live.LiveStatus;
import com.nightgals.live.StreamProvider;
import com.nightgals.live.dto.LiveSessionRequest;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * No broadcast runs longer than two hours.
 *
 * <p>Creators went live and walked away, and nothing ended the room: it stayed
 * open until somebody pressed stop, billing the shared provider minutes the
 * whole time. The sweep ends anything past the limit; this proves it, and proves
 * the two things that make it safe - it meters exactly the limit rather than the
 * sweep's lateness, and ending twice never books minutes twice.
 *
 * <p>Not {@code @Transactional}: the job ends each session in its own
 * transaction, and a test transaction would hide those commits from itself.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.live.max-session-length=PT2H",
        // Off, so the test drives the sweep rather than racing a scheduled one.
        "nightgals.live.overrun-cron=-",
        "nightgals.creator-packages.enabled=false",
})
class LiveOverrunTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired LiveSessionService liveSessionService;
    @Autowired LiveSessionRepository sessionRepository;
    @Autowired LiveOverrunJob overrunJob;
    @Autowired UserRepository userRepository;
    @MockitoSpyBean StreamProvider streamProvider;

    @Test
    @DisplayName("A broadcast past two hours is ended by the sweep")
    void pastTheLimitIsEnded() {
        UUID id = liveSince(creator(), Duration.ofHours(3));

        overrunJob.endOverrunning();

        LiveSession session = sessionRepository.findById(id).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(LiveStatus.ENDED);
    }

    @Test
    @DisplayName("It is metered at exactly two hours, not at when the sweep noticed")
    void meteredAtTheLimit() {
        UUID id = liveSince(creator(), Duration.ofHours(3));

        overrunJob.endOverrunning();

        LiveSession session = sessionRepository.findById(id).orElseThrow();
        // Three hours on the clock, two billed: charging the creator for the gap
        // between her time running out and the job getting there would bill her
        // for our scheduling.
        assertThat(session.actualMinutes()).isEqualTo(120);
    }

    @Test
    @DisplayName("Ending it closes the room at the provider, which is what stops the billing")
    void theRoomIsTornDown() {
        UUID id = liveSince(creator(), Duration.ofHours(3));

        overrunJob.endOverrunning();

        verify(streamProvider, atLeastOnce()).teardown(any(LiveSession.class));
        assertThat(sessionRepository.findById(id).orElseThrow().getStatus()).isEqualTo(LiveStatus.ENDED);
    }

    @Test
    @DisplayName("A broadcast still inside the limit is left alone")
    void insideTheLimitIsUntouched() {
        UUID id = liveSince(creator(), Duration.ofMinutes(90));

        overrunJob.endOverrunning();

        assertThat(sessionRepository.findById(id).orElseThrow().getStatus()).isEqualTo(LiveStatus.LIVE);
    }

    @Test
    @DisplayName("Pressing End after the sweep got there first does not book the minutes twice")
    void endingTwiceDoesNotDoubleCharge() {
        User host = creator();
        UUID id = liveSince(host, Duration.ofHours(3));

        overrunJob.endOverrunning();
        var afterSweep = sessionRepository.findById(id).orElseThrow().getEndedAt();

        // Her studio's End button, arriving a moment after the sweep.
        liveSessionService.end(reload(host), id);

        // Unchanged: the second call saw it was already over and did nothing.
        assertThat(sessionRepository.findById(id).orElseThrow().getEndedAt()).isEqualTo(afterSweep);
    }

    @Test
    @DisplayName("Pressing End closes the room too — it never used to")
    void endingByHandTearsDownTheRoom() {
        User host = creator();
        UUID id = liveSince(host, Duration.ofMinutes(10));

        liveSessionService.end(reload(host), id);

        // Before this, End only changed a database row. LiveKit kept carrying —
        // and charging for — whatever her browser was still sending.
        verify(streamProvider, atLeastOnce()).teardown(any(LiveSession.class));
    }

    // ------------------------------------------------------------- helpers

    /** A session that went live {@code ago} before now. */
    private UUID liveSince(User host, Duration ago) {
        var created = liveSessionService.create(host,
                new LiveSessionRequest("Tonight", null, null, null, null, 3_000L));
        liveSessionService.start(reload(host), created.id());

        LiveSession session = sessionRepository.findById(created.id()).orElseThrow();
        session.setStartedAt(Instant.now().minus(ago).truncatedTo(ChronoUnit.SECONDS));
        sessionRepository.saveAndFlush(session);
        return created.id();
    }

    private User creator() {
        String email = "live-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Weekend only", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));
        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        return userRepository.saveAndFlush(managed);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
