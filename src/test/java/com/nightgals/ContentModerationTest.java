package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.common.ApiException;
import com.nightgals.mail.EmailService;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaService;
import com.nightgals.media.MediaStatus;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * A moderator working on somebody else's gallery.
 *
 * <p>Two ways to remove something and they are not interchangeable: a takedown
 * hides an item and can be undone, a delete destroys the row and the file and
 * cannot. Both require a reason, and both tell the creator — content that
 * vanishes with no message is indistinguishable from a bug, and somebody who is
 * not told why cannot avoid the same removal tomorrow.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ContentModerationTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired UserRepository userRepository;

    @MockitoBean EmailService emailService;

    @Test
    @DisplayName("Staff see her whole gallery, taken-down items included")
    void staffSeeEverythingIncludingWhatIsHidden() {
        User creator = creatorWithWork();
        UUID clip = mediaService.upload(reload(creator), MediaType.VIDEO,
                file("v.mp4", "video/mp4"), null, ContentTier.EXCLUSIVE, 5_000L).id();

        mediaService.takeDown(clip, "Third party did not consent");

        // The public gallery drops it; the moderation view must not, or a
        // moderator cannot see what they removed or put it back.
        assertThat(mediaService.listPublic(creator.getId(), null))
                .noneSatisfy(m -> assertThat(m.id()).isEqualTo(clip));
        assertThat(mediaService.listOwn(creator.getId()))
                .anySatisfy(m -> {
                    assertThat(m.id()).isEqualTo(clip);
                    assertThat(m.status()).isEqualTo(MediaStatus.REJECTED);
                    assertThat(m.rejectionReason()).isEqualTo("Third party did not consent");
                });
    }

    @Test
    @DisplayName("A takedown emails the creator the reason")
    void takedownNotifiesTheOwner() {
        User creator = creatorWithWork();
        UUID photo = mediaService.listOwn(creator.getId()).getFirst().id();

        mediaService.takeDown(photo, "Nudity in a free preview");

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendContentRemoved(
                eq(creator.getEmail()), eq(creator.getUsername()), any(),
                reason.capture(), eq(false));
        assertThat(reason.getValue()).isEqualTo("Nudity in a free preview");
    }

    @Test
    @DisplayName("A permanent delete removes it for good and says so")
    void deleteIsPermanentAndNotifies() {
        User creator = creatorWithWork();
        UUID photo = mediaService.listOwn(creator.getId()).getFirst().id();

        mediaService.deleteAsAdmin(photo, "Reported and confirmed");

        assertThat(mediaService.listOwn(creator.getId()))
                .noneSatisfy(m -> assertThat(m.id()).isEqualTo(photo));
        // `permanent = true` is what makes the email say "deleted" rather than
        // "taken down" — the difference matters to whoever made the picture.
        verify(emailService).sendContentRemoved(
                eq(creator.getEmail()), eq(creator.getUsername()), any(),
                eq("Reported and confirmed"), eq(true));
    }

    @Test
    @DisplayName("Neither removal is allowed without a reason")
    void aReasonIsAlwaysRequired() {
        User creator = creatorWithWork();
        UUID photo = mediaService.listOwn(creator.getId()).getFirst().id();

        assertThatThrownBy(() -> mediaService.takeDown(photo, "  "))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> mediaService.deleteAsAdmin(photo, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Restoring puts a taken-down item back in the public gallery")
    void restoreBringsItBack() {
        User creator = creatorWithWork();
        UUID photo = mediaService.listOwn(creator.getId()).getFirst().id();

        mediaService.takeDown(photo, "Checking something");
        mediaService.restore(photo);

        assertThat(mediaService.listPublic(creator.getId(), null))
                .anySatisfy(m -> assertThat(m.id()).isEqualTo(photo));
    }

    // ------------------------------------------------------------- helpers

    private User creatorWithWork() {
        String email = "mod-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Weekend only", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));
        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        User saved = userRepository.saveAndFlush(managed);
        mediaService.upload(saved, MediaType.PHOTO, file("p.jpg", "image/jpeg"),
                null, ContentTier.FREE, null);
        return reload(saved);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MultipartFile file(String name, String type) {
        return new MockMultipartFile("file", name, type, new byte[] {1, 2, 3});
    }
}
