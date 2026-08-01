package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.JwtService;
import com.nightgals.auth.dto.RegisterRequest;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the upload endpoint through the real HTTP stack.
 *
 * <p>The rest of the suite calls services directly, which cannot catch
 * request-binding faults - a multipart text field arrives as
 * {@code application/octet-stream}, so a non-file value has to bind as a request
 * parameter rather than a part. That bug was invisible to every service-level
 * test and only showed up against a running server.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MediaUploadWebTest {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    private String bearer;

    @BeforeEach
    void createApprovedCreator() {
        String email = "creator-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        profileService.createOrUpdate(user, new ProfileRequest(
                null, null, LocalDate.of(1995, 2, 2),
                Gender.FEMALE, "Nairobi", "Kenya", null, null));
        user.setVerificationStatus(VerificationStatus.APPROVED);
        bearer = "Bearer " + jwtService.issueAccessToken(userRepository.save(user));
    }

    @Test
    @DisplayName("Uploading with tier and caption as form fields binds correctly")
    void uploadsWithTierAndCaption() throws Exception {
        // First photo is forced FREE because it becomes the profile picture.
        mockMvc.perform(multipart("/api/v1/me/media/photos")
                        .file(photo())
                        .param("tier", "EXCLUSIVE")
                        .param("caption", "my first")
                        .header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.caption").value("my first"));

        // The second honours the requested tier.
        mockMvc.perform(multipart("/api/v1/me/media/photos")
                        .file(photo())
                        .param("tier", "EXCLUSIVE")
                        .header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value("EXCLUSIVE"))
                .andExpect(jsonPath("$.locked").value(false));

        mockMvc.perform(multipart("/api/v1/me/media/photos")
                        .file(photo())
                        .param("tier", "FREE")
                        .header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value("FREE"));
    }

    @Test
    @DisplayName("Omitting tier defaults to EXCLUSIVE")
    void tierIsOptional() throws Exception {
        mockMvc.perform(multipart("/api/v1/me/media/photos").file(photo())
                .header("Authorization", bearer)).andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/v1/me/media/photos").file(photo())
                        .header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value("EXCLUSIVE"));
    }

    @Test
    @DisplayName("An unparseable tier is a 400, not a 500")
    void badTierIsClientError() throws Exception {
        mockMvc.perform(multipart("/api/v1/me/media/photos")
                        .file(photo())
                        .param("tier", "PREMIUM")
                        .header("Authorization", bearer))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("The browse feed is reachable with no Authorization header")
    void feedIsPublic() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/members"))
                .andExpect(status().isOk());
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "p.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3});
    }
}
