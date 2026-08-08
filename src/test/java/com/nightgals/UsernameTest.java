package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.common.ApiException;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.UsernameService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The privacy promise: a member is identified to other members by a generated
 * handle, and nothing they entered during identity verification leaks out.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class UsernameTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired UsernameService usernameService;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("Registration assigns a handle automatically")
    void handleAssignedAtRegistration() {
        var response = authService.register(new RegisterRequest(email(), "correct-horse-9", AccountType.CREATOR, null), null);

        assertThat(response.auth().username()).isNotBlank();
        assertThat(response.auth().username()).matches("^[A-Za-z][A-Za-z0-9_]{2,29}$");
    }

    @Test
    @DisplayName("Handles are unique across accounts")
    void handlesAreUnique() {
        var first = authService.register(new RegisterRequest(email(), "correct-horse-9", AccountType.CREATOR, null), null);
        var second = authService.register(new RegisterRequest(email(), "correct-horse-9", AccountType.CREATOR, null), null);

        assertThat(first.auth().username()).isNotEqualTo(second.auth().username());
    }

    @Test
    @DisplayName("Another member's profile exposes the handle and display name, never the date of birth")
    void publicProfileHidesIdentifyingDetail() {
        User owner = register();
        profileService.createOrUpdate(owner, new ProfileRequest(
                "Amina", "Afrobeats and rooftop bars", LocalDate.of(1998, 4, 12),
                Gender.FEMALE, "Nairobi", "Kenya", null, null));

        // An owner-or-staff view keeps everything.
        var ownView = profileService.getOwn(owner.getId());
        assertThat(ownView.displayName()).isEqualTo("Amina");
        assertThat(ownView.dateOfBirth()).isEqualTo(LocalDate.of(1998, 4, 12));

        // Approve the owner so they are visible to others at all.
        approve(owner);
        // Browsing now requires the viewer to be verified too.
        User viewer = register();
        approve(viewer);
        viewer = reload(viewer);

        var publicView = profileService.getPublic(owner.getId(), viewer);
        assertThat(publicView.username()).isEqualTo(reload(owner).getUsername());
        // Published as of V17: the display name is the name shown under the
        // profile picture. It used to be withheld here, and this assertion is
        // what caught the change - deliberate, not a regression.
        assertThat(publicView.displayName()).isEqualTo("Amina");
        assertThat(publicView.dateOfBirth()).isNull();
        // Age is coarse enough to share; the exact birth date is not.
        assertThat(publicView.age()).isEqualTo(ownView.age());
    }

    @Test
    @DisplayName("A profile can be created with no nickname at all")
    void nicknameIsOptional() {
        User user = register();
        var profile = profileService.createOrUpdate(user, new ProfileRequest(
                null, null, LocalDate.of(1996, 3, 3),
                Gender.FEMALE, "Nairobi", "Kenya", null, null));

        assertThat(profile.displayName()).isNull();
        assertThat(profile.username()).isNotBlank();
    }

    @Test
    @DisplayName("Suggestions are distinct and well-formed")
    void suggestionsAreUsable() {
        List<String> suggestions = usernameService.suggest(5);

        assertThat(suggestions).hasSize(5).doesNotHaveDuplicates();
        assertThat(suggestions).allMatch(s -> s.matches("^[A-Za-z][A-Za-z0-9_]{2,29}$"));
    }

    @Test
    @DisplayName("A member can claim a free handle")
    void canClaimHandle() {
        User user = register();
        String claimed = usernameService.change(user, "NairobiNights");

        assertThat(claimed).isEqualTo("NairobiNights");
        assertThat(reload(user).getUsername()).isEqualTo("NairobiNights");
    }

    @Test
    @DisplayName("Handles that impersonate the platform are refused")
    void reservedHandlesRefused() {
        User user = register();

        assertThatThrownBy(() -> usernameService.change(user, "admin"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not available");
        assertThatThrownBy(() -> usernameService.change(user, "NightgalsSupport"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not available");
    }

    @Test
    @DisplayName("Malformed handles are refused")
    void malformedHandlesRefused() {
        User user = register();

        assertThatThrownBy(() -> usernameService.change(user, "9lives"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("start with a letter");
        assertThatThrownBy(() -> usernameService.change(user, "has spaces"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> usernameService.change(user, "no"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("A handle already held by someone else is refused")
    void takenHandleRefused() {
        User first = register();
        usernameService.change(first, "SameHandle");

        User second = register();
        assertThatThrownBy(() -> usernameService.change(second, "samehandle"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already taken");
    }

    @Test
    @DisplayName("The change cooldown blocks a second change")
    void cooldownBlocksSecondChange() {
        User user = register();
        usernameService.change(user, "FirstChoice");

        assertThatThrownBy(() -> usernameService.change(reload(user), "SecondChoice"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("change your username again after");
    }

    // ------------------------------------------------------------- helpers

    private String email() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private User register() {
        String email = email();
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private void approve(User user) {
        User managed = reload(user);
        managed.setVerificationStatus(com.nightgals.user.VerificationStatus.APPROVED);
        userRepository.save(managed);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
