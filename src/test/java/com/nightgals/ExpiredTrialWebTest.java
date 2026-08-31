package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.JwtService;
import com.nightgals.auth.dto.RegisterRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a signed-in member with a spent trial actually gets back over HTTP.
 *
 * <p>{@code TrialTest} asks the entitlement service the same question and has
 * always answered it correctly, which is not the same as proving the answer
 * survives the trip out through a controller. The reported fault was that
 * signing in opened paid content and kept it open after the seven days ran out,
 * so this drives the three surfaces a viewer actually looks at - a creator's
 * gallery, the public video wall, and the file endpoint itself - with a real
 * bearer token on an account whose trial is a day gone.
 *
 * <p>Every assertion here is about the <em>expired</em> case. The trial working
 * while it is running is deliberate and is covered in {@code TrialTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.monetization.enabled=true",
        "nightgals.monetization.free-trial=P7D",
})
class ExpiredTrialWebTest {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    private User creator;
    private UUID paidClip;
    private String expiredBearer;

    @BeforeEach
    void aCreatorWithSomethingToSell() {
        creator = approvedCreator();
        // The first photo is forced FREE - it becomes the profile picture.
        mediaService.upload(creator, MediaType.PHOTO, file("p.jpg", "image/jpeg"),
                null, ContentTier.FREE, null);
        paidClip = mediaService.upload(reload(creator), MediaType.VIDEO, file("v.mp4", "video/mp4"),
                null, ContentTier.EXCLUSIVE, 9_000L).id();

        expiredBearer = "Bearer " + jwtService.issueAccessToken(viewerWithSpentTrial());
    }

    @Test
    @DisplayName("The gallery comes back locked, with a price and no file")
    void galleryStaysLocked() throws Exception {
        mockMvc.perform(get("/api/v1/members/{id}/media", creator.getId())
                        .header("Authorization", expiredBearer))
                .andExpect(status().isOk())
                // The free preview opens; the clip she is selling does not.
                .andExpect(jsonPath("$[?(@.tier == 'EXCLUSIVE')].locked").value(true))
                .andExpect(jsonPath("$[?(@.tier == 'EXCLUSIVE')].priceMinor").value(9000))
                // Absent, not null: the serialiser drops empty fields, so there is
                // no key to read rather than a key reading null. Either way there
                // is nothing to fetch.
                .andExpect(jsonPath("$[?(@.tier == 'EXCLUSIVE')].url").doesNotExist());
    }

    @Test
    @DisplayName("The video wall carries no URL for a clip that was not paid for")
    void videoWallStaysLocked() throws Exception {
        mockMvc.perform(get("/api/v1/videos").param("tier", "EXCLUSIVE")
                        .header("Authorization", expiredBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].locked").value(true))
                .andExpect(jsonPath("$.content[0].url").doesNotExist());
    }

    @Test
    @DisplayName("Asking for the bytes directly is refused, not merely hidden")
    void theFileItselfIsRefused() throws Exception {
        // The one that matters. Hiding a URL is a client-side courtesy; this is
        // the server saying no to somebody who guessed the address.
        mockMvc.perform(get("/api/v1/media/{id}/file", paidClip)
                        .header("Authorization", expiredBearer))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    @DisplayName("A signed-out visitor gets exactly the same answer")
    void anonymousIsTreatedTheSame() throws Exception {
        mockMvc.perform(get("/api/v1/media/{id}/file", paidClip))
                .andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------- helpers

    private User viewerWithSpentTrial() {
        String email = "spent-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.VIEWER, null), null);
        User viewer = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        viewer.setTrialEndsAt(Instant.now().minus(1, ChronoUnit.DAYS));
        return userRepository.saveAndFlush(viewer);
    }

    private User approvedCreator() {
        String email = "seller-" + UUID.randomUUID() + "@example.com";
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

    private MockMultipartFile file(String name, String type) {
        return new MockMultipartFile("file", name, type, new byte[] {1, 2, 3});
    }
}
