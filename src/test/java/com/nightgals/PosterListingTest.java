package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.mail.EmailService;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaService;
import com.nightgals.media.MediaType;
import com.nightgals.media.dto.PosterResponse;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The moderation listing's first screen: who has posted, and how much.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class PosterListingTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    @MockitoBean EmailService emailService;

    @Test
    @DisplayName("A poster's row carries their totals, and counts what was taken down")
    void totalsPerPoster() {
        User creator = creator();
        UUID photo = upload(creator, MediaType.PHOTO);
        upload(creator, MediaType.PHOTO);
        UUID clip = upload(creator, MediaType.VIDEO);
        mediaService.takeDown(clip, "Third party did not consent");

        PosterResponse row = rowFor(list("", false), creator);

        assertThat(row.posts()).isEqualTo(3);
        assertThat(row.photos()).isEqualTo(2);
        assertThat(row.videos()).isEqualTo(1);
        assertThat(row.takenDown()).isEqualTo(1);
        assertThat(row.username()).isEqualTo(creator.getUsername());
        assertThat(row.email()).isEqualTo(creator.getEmail());
        assertThat(row.city()).isEqualTo("Douala");
        assertThat(row.suspended()).isFalse();
        assertThat(row.lastPostAt()).isNotNull();
        assertThat(row.thumbnailUrl()).startsWith("/api/v1/media/").endsWith("/file");
        assertThat(photo).isNotNull();
    }

    @Test
    @DisplayName("Somebody who has never posted is not listed")
    void nonPostersAreLeftOut() {
        User quiet = creator();
        User busy = creator();
        upload(busy, MediaType.PHOTO);

        List<PosterResponse> rows = list("", false);

        assertThat(rows).anyMatch(r -> r.userId().equals(busy.getId()));
        assertThat(rows).noneMatch(r -> r.userId().equals(quiet.getId()));
    }

    @Test
    @DisplayName("A taken-down photo is never the thumbnail, and a video never is either")
    void thumbnailSkipsHiddenAndVideo() {
        User creator = creator();
        UUID photo = upload(creator, MediaType.PHOTO);
        upload(creator, MediaType.VIDEO);
        mediaService.takeDown(photo, "Not hers");

        assertThat(rowFor(list("", false), creator).thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("Search matches the handle or the email, and the filter keeps only people with takedowns")
    void searchAndFilter() {
        User clean = creator();
        upload(clean, MediaType.PHOTO);
        User flagged = creator();
        mediaService.takeDown(upload(flagged, MediaType.PHOTO), "Nudity in a free preview");

        assertThat(list(clean.getUsername().toLowerCase(), false))
                .extracting(PosterResponse::userId).containsExactly(clean.getId());
        assertThat(list(flagged.getEmail(), false))
                .extracting(PosterResponse::userId).containsExactly(flagged.getId());

        List<PosterResponse> onlyFlagged = list("", true);
        assertThat(onlyFlagged).anyMatch(r -> r.userId().equals(flagged.getId()));
        assertThat(onlyFlagged).noneMatch(r -> r.userId().equals(clean.getId()));
    }

    @Test
    @DisplayName("The total counts people, not posts, so paging is right")
    void countsPeople() {
        User a = creator();
        upload(a, MediaType.PHOTO);
        upload(a, MediaType.PHOTO);
        upload(a, MediaType.PHOTO);
        entityManager.flush();

        var page = mediaService.posters(a.getEmail(), false, PageRequest.of(0, 25));

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).hasSize(1);
    }

    // ------------------------------------------------------------- helpers

    /** Native queries do not see unflushed entities, so everything is written out first. */
    private List<PosterResponse> list(String q, boolean takenDownOnly) {
        entityManager.flush();
        return mediaService.posters(q, takenDownOnly, PageRequest.of(0, 200)).content();
    }

    private PosterResponse rowFor(List<PosterResponse> rows, User user) {
        return rows.stream().filter(r -> r.userId().equals(user.getId())).findFirst().orElseThrow();
    }

    private UUID upload(User creator, MediaType type) {
        MultipartFile file = type == MediaType.VIDEO
                ? new MockMultipartFile("file", "v.mp4", "video/mp4", new byte[] {1, 2, 3})
                : new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[] {1, 2, 3});
        return mediaService.upload(reload(creator), type, file, null,
                type == MediaType.VIDEO ? ContentTier.EXCLUSIVE : ContentTier.FREE,
                type == MediaType.VIDEO ? 5_000L : null).id();
    }

    private User creator() {
        String email = "poster-" + UUID.randomUUID() + "@example.com";
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
