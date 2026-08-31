package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileRepository;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.user.AccountType;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import com.nightgals.views.ViewCounterService;
import com.nightgals.views.ViewSubject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What makes a view count, and what does not.
 *
 * <p>The counting is the easy half. The rules are the point: a number that goes
 * up on every render measures how often a page was drawn, which is not what
 * anybody reads it as. So most of what is asserted here is that the number
 * <em>stays still</em>.
 *
 * <p>Deliberately not {@code @Transactional}. The counter writes in its own
 * transaction — it has to, so that a failure cannot roll back the page that
 * triggered it — and a test transaction would hide every one of those writes
 * from itself.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ViewCountTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired ViewCounterService viewCounter;
    @Autowired ProfileRepository profileRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("A visitor counts once, however many times they look")
    void oncePerPersonPerDay() {
        User creator = creator();
        User visitor = viewer();

        for (int i = 0; i < 5; i++) {
            view(creator, visitor);
        }

        // Five requests, one person, one view. Without this a refresh is a view
        // and the number is a page-render counter wearing an audience's name.
        assertThat(views(creator)).isEqualTo(1);
    }

    @Test
    @DisplayName("Two different people count twice")
    void differentPeopleCountSeparately() {
        User creator = creator();
        view(creator, viewer());
        view(creator, viewer());

        assertThat(views(creator)).isEqualTo(2);
    }

    @Test
    @DisplayName("Looking at your own profile is not an audience")
    void theOwnerDoesNotCount() {
        User creator = creator();

        view(creator, creator);

        assertThat(views(creator)).isZero();
    }

    @Test
    @DisplayName("Staff are working, not browsing")
    void staffDoNotCount() {
        User creator = creator();
        User admin = viewer();
        admin.setRole(Role.ADMIN);
        userRepository.saveAndFlush(admin);

        view(creator, reload(admin));

        // A moderation queue would otherwise put the most-reviewed accounts at
        // the top of "most viewed", which is the opposite of what that list is for.
        assertThat(views(creator)).isZero();
    }

    @Test
    @DisplayName("Signed-out visitors are told apart by address, not lumped together")
    void anonymousVisitorsAreSeparated() {
        User creator = creator();

        viewCounter.record(ViewSubject.PROFILE, creator.getId(), null, creator.getId(),
                requestFrom("41.202.207.9", "Firefox"));
        viewCounter.record(ViewSubject.PROFILE, creator.getId(), null, creator.getId(),
                requestFrom("41.202.207.9", "Firefox"));
        viewCounter.record(ViewSubject.PROFILE, creator.getId(), null, creator.getId(),
                requestFrom("102.16.4.51", "Safari"));

        // The same visitor twice, then a different one.
        assertThat(views(creator)).isEqualTo(2);
    }

    @Test
    @DisplayName("A view that cannot be attributed to anybody is not counted")
    void unattributableViewsAreDropped() {
        User creator = creator();

        // No principal and no request: nothing to deduplicate on, so counting it
        // would make the number worse rather than more complete.
        viewCounter.record(ViewSubject.PROFILE, creator.getId(), null, creator.getId(), null);

        assertThat(views(creator)).isZero();
    }

    // ------------------------------------------------------------- helpers

    private void view(User subject, User viewer) {
        viewCounter.record(ViewSubject.PROFILE, subject.getId(), viewer, subject.getId(),
                requestFrom("41.202.207.9", "Firefox"));
    }

    private long views(User creator) {
        return profileRepository.findByUserId(creator.getId()).orElseThrow().getViewCount();
    }

    private MockHttpServletRequest requestFrom(String ip, String agent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        request.addHeader("User-Agent", agent);
        return request;
    }

    private User creator() {
        User user = register(AccountType.CREATOR);
        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Weekend only", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));
        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        return userRepository.saveAndFlush(managed);
    }

    private User viewer() {
        return register(AccountType.VIEWER);
    }

    private User register(AccountType type) {
        String email = "views-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", type, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
