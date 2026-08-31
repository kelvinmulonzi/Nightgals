package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.common.ApiException;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaService;
import com.nightgals.media.MediaType;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signing up as a creator when no free trial is running.
 *
 * <p>Onboarding is profile, then first photo, then package. The photo step used
 * to call the publishing gate, which demands the package that comes <em>after</em>
 * it - a door locked from the far side. A new creator could not finish signing
 * up at all: "Choose a package before you post" on step 2, with the package on
 * step 3 and no way to reach it.
 *
 * <p>It stayed invisible for as long as a trial was running, because the trial
 * satisfies that gate. This class turns the trial off, which is the deployment
 * the bug actually appears in - and the default, since {@code FREE_TRIAL}
 * unset means {@code PT0S}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.monetization.enabled=true",
        "nightgals.monetization.free-trial=PT0S",
        "nightgals.creator-packages.enabled=true",
})
@Transactional
class OnboardingWithoutTrialTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("A creator can post her profile picture before she has bought anything")
    void theFirstPhotoNeedsNoPackage() {
        User creator = approvedCreator();
        assertThat(creator.isOnTrial()).isFalse();

        var photo = mediaService.upload(creator, MediaType.PHOTO, image(), null, null, null);

        // Forced free and primary whatever the client asked for, which is exactly
        // why it costs nothing to let it through: it is her card in Discover, and
        // `update` refuses to make a primary photo exclusive.
        assertThat(photo.tier()).isEqualTo(ContentTier.FREE);
        assertThat(photo.primary()).isTrue();
    }

    @Test
    @DisplayName("The second photo still needs one")
    void theSecondPhotoDoesNeedAPackage() {
        User creator = approvedCreator();
        mediaService.upload(creator, MediaType.PHOTO, image(), null, null, null);

        assertThatThrownBy(() ->
                mediaService.upload(reload(creator), MediaType.PHOTO, image(), null, ContentTier.FREE, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Choose a package");
    }

    @Test
    @DisplayName("A video still needs one, even as her very first upload")
    void videoStillNeedsAPackage() {
        User creator = approvedCreator();

        assertThatThrownBy(() ->
                mediaService.upload(creator, MediaType.VIDEO, video(), null, ContentTier.EXCLUSIVE, 5_000L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Choose a package");
    }

    // ------------------------------------------------------------- helpers

    private User approvedCreator() {
        String email = "onboard-" + UUID.randomUUID() + "@example.com";
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

    private MultipartFile image() {
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private MultipartFile video() {
        return new MockMultipartFile("file", "v.mp4", "video/mp4", new byte[] {4, 5, 6});
    }
}
