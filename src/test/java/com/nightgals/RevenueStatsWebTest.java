package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.JwtService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.user.AccountType;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard's door.
 *
 * <p>The service-level test covers the arithmetic; this covers who is allowed to
 * ask. Revenue is admin-only where the review queues are not, and that gap is
 * held open by a method annotation rather than the filter chain - which only
 * requires staff for {@code /admin/**} - so it is worth a test that a moderator
 * really is turned away.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RevenueStatsWebTest {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    @Test
    @DisplayName("An admin gets the series")
    void adminReads() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats/revenue?days=7").header("Authorization", bearerFor(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").exists())
                .andExpect(jsonPath("$.to").exists())
                .andExpect(jsonPath("$.series").isArray());
    }

    @Test
    @DisplayName("A moderator works the queues but does not see the takings")
    void moderatorRefused() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats/revenue").header("Authorization", bearerFor(Role.MODERATOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An ordinary member is refused")
    void memberRefused() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats/revenue").header("Authorization", bearerFor(Role.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Signed out is refused")
    void anonymousRefused() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats/revenue"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("An absurd window is clamped rather than refused")
    void clampsRatherThanRejects() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats/revenue?days=99999").header("Authorization", bearerFor(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    private String bearerFor(Role role) {
        String email = "staff-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.VIEWER, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setRole(role);
        user.setVerificationStatus(VerificationStatus.APPROVED);
        return "Bearer " + jwtService.issueAccessToken(userRepository.save(user));
    }
}
