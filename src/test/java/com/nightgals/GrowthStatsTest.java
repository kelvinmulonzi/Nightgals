package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.stats.StatsService;
import com.nightgals.stats.dto.GrowthResponse;
import com.nightgals.user.AccountType;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.UserStatus;
import com.nightgals.user.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The growth dashboard.
 *
 * <p>What matters here is mostly what the numbers must keep counting: accounts
 * a moderator has since suspended, and stages measured over all time rather
 * than the window the chart happens to be showing.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class GrowthStatsTest {

    @Autowired StatsService statsService;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Test
    @DisplayName("Signups land on today, split by what the account is for")
    void splitsByAccountType() {
        register(AccountType.VIEWER);
        register(AccountType.VIEWER);
        register(AccountType.CREATOR);

        var growth = statsService.growth(7);
        var today = pointOn(growth, TODAY);

        assertThat(today.viewers()).isEqualTo(2);
        assertThat(today.creators()).isEqualTo(1);
        assertThat(today.total()).isEqualTo(3);
        assertThat(growth.signups()).isEqualTo(3);
    }

    @Test
    @DisplayName("Quiet days are present as zeroes, so the axis stays a calendar")
    void fillsGaps() {
        register(AccountType.VIEWER);

        var growth = statsService.growth(7);

        assertThat(growth.from()).isEqualTo(TODAY.minusDays(6));
        assertThat(growth.to()).isEqualTo(TODAY);
        assertThat(growth.points()).hasSize(7);
        assertThat(growth.points().getFirst().date()).isEqualTo(TODAY.minusDays(6));
        assertThat(growth.points().getLast().date()).isEqualTo(TODAY);
        assertThat(pointOn(growth, TODAY.minusDays(4)).total()).isZero();
    }

    @Test
    @DisplayName("A suspended account still signed up, and still counts")
    void suspendedStillCounts() {
        User user = register(AccountType.VIEWER);
        user.setStatus(UserStatus.SUSPENDED);
        userRepository.save(user);

        assertThat(pointOn(statsService.growth(7), TODAY).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("The creator pipeline narrows, stage by stage")
    void funnelNarrows() {
        // Registered only.
        register(AccountType.CREATOR);
        // Submitted, awaiting review.
        User submitted = register(AccountType.CREATOR);
        submitted.setVerificationStatus(VerificationStatus.PENDING_REVIEW);
        userRepository.save(submitted);
        // Submitted and approved.
        User approved = register(AccountType.CREATOR);
        approved.setVerificationStatus(VerificationStatus.APPROVED);
        userRepository.save(approved);

        Map<String, Long> stages = stagesOf(statsService.growth(7));

        assertThat(stages.get("REGISTERED")).isEqualTo(3);
        assertThat(stages.get("IDENTITY_SUBMITTED")).isEqualTo(2);
        assertThat(stages.get("IDENTITY_APPROVED")).isEqualTo(1);
        // Approved, but nobody has bought a package.
        assertThat(stages.get("PUBLISHING")).isZero();

        // The order is the contract: a funnel that does not narrow is not one.
        List<Long> counts = statsService.growth(7).funnel().stream()
                .map(GrowthResponse.FunnelStage::count)
                .toList();
        assertThat(counts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("A rejected creator still submitted, and still counts as having done so")
    void rejectedCountsAsSubmitted() {
        User rejected = register(AccountType.CREATOR);
        rejected.setVerificationStatus(VerificationStatus.REJECTED);
        userRepository.save(rejected);

        Map<String, Long> stages = stagesOf(statsService.growth(7));

        assertThat(stages.get("IDENTITY_SUBMITTED")).isEqualTo(1);
        assertThat(stages.get("IDENTITY_APPROVED")).isZero();
    }

    @Test
    @DisplayName("Viewers stay out of the pipeline they were never asked to enter")
    void viewersExcludedFromFunnel() {
        register(AccountType.VIEWER);
        register(AccountType.VIEWER);
        register(AccountType.CREATOR);

        // Three signups on the chart, one account in the pipeline.
        assertThat(pointOn(statsService.growth(7), TODAY).total()).isEqualTo(3);
        assertThat(stagesOf(statsService.growth(7)).get("REGISTERED")).isEqualTo(1);
    }

    @Test
    @DisplayName("An approved creator with no email verified does not break the ordering")
    void emailIsNotAStage() {
        // The bug this guards: counting flags independently let "approved" exceed
        // "email verified", and the bars stopped narrowing.
        User approved = register(AccountType.CREATOR);
        approved.setEmailVerified(false);
        approved.setVerificationStatus(VerificationStatus.APPROVED);
        userRepository.save(approved);

        List<Long> counts = statsService.growth(7).funnel().stream()
                .map(GrowthResponse.FunnelStage::count)
                .toList();

        assertThat(counts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(statsService.growth(7).mix().emailVerified()).isZero();
    }

    @Test
    @DisplayName("Staff are left out of both panels rather than dragging them down")
    void staffExcluded() {
        User staff = register(AccountType.CREATOR);
        staff.setRole(Role.ADMIN);
        userRepository.save(staff);
        register(AccountType.CREATOR);

        assertThat(stagesOf(statsService.growth(7)).get("REGISTERED")).isEqualTo(1);
        assertThat(pointOn(statsService.growth(7), TODAY).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("The funnel ignores the window, because later steps happen after it")
    void funnelIsNotWindowed() {
        register(AccountType.CREATOR);

        // One day of chart, but the funnel still sees the account.
        var narrow = statsService.growth(1);

        assertThat(narrow.points()).hasSize(1);
        assertThat(stagesOf(narrow).get("REGISTERED")).isEqualTo(1);
    }

    @Test
    @DisplayName("The mix counts composition, not progress")
    void mixCountsComposition() {
        register(AccountType.VIEWER);
        register(AccountType.CREATOR);
        register(AccountType.CREATOR);

        var mix = statsService.growth(7).mix();

        assertThat(mix.viewers()).isEqualTo(1);
        assertThat(mix.creators()).isEqualTo(2);
        assertThat(mix.viaGoogle()).isZero();
        assertThat(mix.payingViewers()).isZero();
    }

    @Test
    @DisplayName("The window is clamped like the revenue one")
    void clampsWindow() {
        assertThat(statsService.growth(0).from()).isEqualTo(TODAY);
        assertThat(statsService.growth(10_000).from())
                .isEqualTo(TODAY.minusDays(StatsService.MAX_DAYS - 1L));
    }

    // ------------------------------------------------------------- helpers

    private GrowthResponse.SignupPoint pointOn(GrowthResponse growth, LocalDate date) {
        return growth.points().stream()
                .filter(p -> p.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No point for " + date));
    }

    private Map<String, Long> stagesOf(GrowthResponse growth) {
        return growth.funnel().stream().collect(Collectors.toMap(
                GrowthResponse.FunnelStage::key,
                GrowthResponse.FunnelStage::count,
                (a, b) -> a,
                java.util.LinkedHashMap::new));
    }

    private User register(AccountType type) {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", type, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }
}
