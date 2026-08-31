package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.LoginRequest;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.common.ApiException;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaService;
import com.nightgals.media.MediaType;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.user.AccountType;
import com.nightgals.user.AdminUserService;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.UserStatus;
import com.nightgals.user.VerificationStatus;
import com.nightgals.user.dto.SuspendUserRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Burning an account, and what it is supposed to take with it.
 *
 * <p>{@code SUSPENDED} existed long before there was a way to set it, and on its
 * own it only refused sign-in. That is the smallest half of the job: the point
 * of removing somebody is that their work stops being on the site, and a status
 * that hides nothing leaves a burned creator's profile, gallery and reels in
 * front of anyone holding a link.
 *
 * <p>So most of what is asserted here is absence.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountBurnTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired AdminUserService adminUserService;
    @Autowired UserRepository userRepository;
    @Autowired com.nightgals.reels.ReelService reelService;

    @Test
    @DisplayName("A burned creator's gallery is empty to the public and to a member")
    void burningEmptiesTheGallery() {
        User creator = creatorWithWork();
        User visitor = register(AccountType.VIEWER);

        assertThat(mediaService.listPublic(creator.getId(), visitor)).isNotEmpty();

        burn(creator);

        assertThat(mediaService.listPublic(creator.getId(), reload(visitor))).isEmpty();
        assertThat(mediaService.listPublic(creator.getId(), null)).isEmpty();
    }

    @Test
    @DisplayName("A burned creator's profile stops answering on its own URL")
    void burningHidesTheProfile() {
        User creator = creatorWithWork();
        User visitor = register(AccountType.VIEWER);

        assertThat(profileService.getPublic(creator.getId(), visitor)).isNotNull();

        burn(creator);

        assertThatThrownBy(() -> profileService.getPublic(creator.getId(), reload(visitor)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("A burned creator's reels drop off the landing page")
    void burningTakesTheReelsDown() {
        User creator = creatorWithWork();
        reelService.post(creator,
                new MockMultipartFile("file", "r.mp4", "video/mp4", new byte[] {1, 2, 3}), "tonight");

        assertThat(reelService.live()).anySatisfy(
                r -> assertThat(r.creatorId()).isEqualTo(creator.getId()));

        burn(creator);

        // The reel has not expired - it is simply no longer hers to show.
        assertThat(reelService.live()).noneSatisfy(
                r -> assertThat(r.creatorId()).isEqualTo(creator.getId()));
    }

    @Test
    @DisplayName("Staff still see the work, because that is how the call gets reviewed")
    void staffKeepLooking() {
        User creator = creatorWithWork();
        User admin = admin();

        burn(creator);

        assertThat(mediaService.listPublic(creator.getId(), admin)).isNotEmpty();
        assertThat(profileService.getPublic(creator.getId(), admin)).isNotNull();
    }

    @Test
    @DisplayName("A burned account cannot sign in")
    void burningRefusesSignIn() {
        String email = "burn-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.VIEWER, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        burn(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "correct-horse-9"), null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Restoring puts everything back, and keeps the record of the burn")
    void restoringPutsItBack() {
        User creator = creatorWithWork();
        User visitor = register(AccountType.VIEWER);

        burn(creator);
        var restored = adminUserService.restore(creator.getId(), admin());

        assertThat(restored.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(restored.suspended()).isFalse();
        // The paperwork survives on purpose: a second incident is exactly when
        // somebody wants to know there was a first.
        assertThat(restored.suspendedReason()).isNotBlank();
        assertThat(restored.suspendedAt()).isNotNull();

        assertThat(mediaService.listPublic(creator.getId(), reload(visitor))).isNotEmpty();
        assertThat(profileService.getPublic(creator.getId(), reload(visitor))).isNotNull();
    }

    @Test
    @DisplayName("An administrator cannot burn themselves, or another administrator")
    void theConsoleRefusesTheTwoWaysItCouldLockItselfOut() {
        User admin = admin();
        User other = admin();

        assertThatThrownBy(() -> adminUserService.suspend(admin.getId(), reason(), admin))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("your own");

        assertThatThrownBy(() -> adminUserService.suspend(other.getId(), reason(), admin))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Administrators");
    }

    @Test
    @DisplayName("Burning twice is refused rather than silently repeated")
    void burningTwiceIsRefused() {
        User creator = creatorWithWork();
        burn(creator);

        assertThatThrownBy(() -> adminUserService.suspend(creator.getId(), reason(), admin()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already");
    }

    @Test
    @DisplayName("The console can find an account and count what it is looking at")
    void searchAndCounts() {
        User creator = creatorWithWork();
        burn(creator);

        var found = adminUserService.list(creator.getEmail(), null, PageRequest.of(0, 10));
        assertThat(found.content()).singleElement()
                .satisfies(row -> {
                    assertThat(row.suspended()).isTrue();
                    assertThat(row.suspendedReason()).isNotBlank();
                    assertThat(row.suspendedById()).isNotNull();
                });

        assertThat(adminUserService.counts().get("suspended")).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------- helpers

    private void burn(User target) {
        adminUserService.suspend(target.getId(), reason(), admin());
    }

    private SuspendUserRequest reason() {
        return new SuspendUserRequest("Posted content involving somebody who did not consent");
    }

    private User admin() {
        User staff = register(AccountType.VIEWER);
        staff.setRole(Role.ADMIN);
        return userRepository.saveAndFlush(staff);
    }

    private User creatorWithWork() {
        User creator = register(AccountType.CREATOR);
        profileService.createOrUpdate(creator, new ProfileRequest(
                null, "Weekend only", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));
        User managed = reload(creator);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        User saved = userRepository.saveAndFlush(managed);

        mediaService.upload(saved, MediaType.PHOTO,
                new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[] {1, 2, 3}),
                null, ContentTier.FREE, null);
        return reload(saved);
    }

    private User register(AccountType type) {
        String email = "burn-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", type, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
