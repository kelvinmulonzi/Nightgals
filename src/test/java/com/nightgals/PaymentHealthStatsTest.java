package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.Purchase;
import com.nightgals.billing.PurchaseRepository;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.billing.PurchaseType;
import com.nightgals.stats.StatsService;
import com.nightgals.stats.dto.PaymentHealthResponse;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payment-health dashboard's arithmetic.
 *
 * <p>What is pinned here is mostly what a settle rate must <em>not</em> do:
 * count a cancellation as a failure, guess at a pending payment, or draw a
 * quiet day as an outage. A rate that does any of those turns an ordinary week
 * into an incident, and the panel stops being worth looking at.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class PaymentHealthStatsTest {

    @Autowired StatsService statsService;
    @Autowired PurchaseRepository purchaseRepository;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private User creator;

    @Test
    @DisplayName("The settle rate is settled over what actually resolved")
    void settleRateIgnoresWhatHasNotResolved() {
        User buyer = register();
        attempt(buyer, PurchaseStatus.COMPLETED, "stripe", null, TODAY);
        attempt(buyer, PurchaseStatus.COMPLETED, "stripe", null, TODAY);
        attempt(buyer, PurchaseStatus.COMPLETED, "stripe", null, TODAY);
        attempt(buyer, PurchaseStatus.FAILED, "stripe", "card_declined", TODAY);
        // Neither of these belongs in the rate: one person changed their mind,
        // the other has not finished.
        attempt(buyer, PurchaseStatus.CANCELLED, "stripe", null, TODAY);
        attempt(buyer, PurchaseStatus.PENDING, "stripe", null, TODAY);

        var health = statsService.paymentHealth(7);

        assertThat(health.summary().attempts()).isEqualTo(6);
        assertThat(health.summary().settled()).isEqualTo(3);
        assertThat(health.summary().failed()).isEqualTo(1);
        assertThat(health.summary().cancelled()).isEqualTo(1);
        assertThat(health.summary().pending()).isEqualTo(1);
        // 3 of 4 resolved, not 3 of 6.
        assertThat(health.summary().settleRatePercent()).isEqualTo(75.0);
    }

    @Test
    @DisplayName("A day nothing resolved on has no rate, rather than a rate of zero")
    void quietDayIsNotDrawnAsAnOutage() {
        User buyer = register();
        attempt(buyer, PurchaseStatus.COMPLETED, "stripe", null, TODAY);

        var health = statsService.paymentHealth(3);

        assertThat(health.points()).hasSize(3);
        var today = health.points().get(2);
        var quiet = health.points().get(0);

        assertThat(today.date()).isEqualTo(TODAY);
        assertThat(today.settleRatePercent()).isEqualTo(100.0);
        // The point exists so the line is continuous, but it states no rate.
        assertThat(quiet.settled()).isZero();
        assertThat(quiet.settleRatePercent()).isNull();
    }

    @Test
    @DisplayName("Every day in the window is present, oldest first")
    void windowIsDense() {
        var health = statsService.paymentHealth(14);

        assertThat(health.points()).hasSize(14);
        assertThat(health.points().get(0).date()).isEqualTo(TODAY.minusDays(13));
        assertThat(health.points().get(13).date()).isEqualTo(TODAY);
        assertThat(health.from()).isEqualTo(TODAY.minusDays(13));
        assertThat(health.to()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("Providers are reported apart, so one failing does not hide behind the other working")
    void providersAreSplit() {
        User buyer = register();
        attempt(buyer, PurchaseStatus.COMPLETED, "stripe", null, TODAY);
        attempt(buyer, PurchaseStatus.COMPLETED, "stripe", null, TODAY);
        attempt(buyer, PurchaseStatus.FAILED, "momo", "timeout", TODAY);
        attempt(buyer, PurchaseStatus.FAILED, "momo", "timeout", TODAY);
        attempt(buyer, PurchaseStatus.COMPLETED, "momo", null, TODAY);

        var health = statsService.paymentHealth(7);

        var stripe = health.providers().stream()
                .filter(p -> p.provider().equals("stripe")).findFirst().orElseThrow();
        var momo = health.providers().stream()
                .filter(p -> p.provider().equals("momo")).findFirst().orElseThrow();

        assertThat(stripe.settleRatePercent()).isEqualTo(100.0);
        assertThat(momo.settleRatePercent()).isEqualTo(33.3);
        // Blended, the pair look like a 60% week and neither number is actionable.
        assertThat(health.summary().settleRatePercent()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("Failure causes are grouped, commonest first, with unrecorded ones kept")
    void failureReasonsAreRanked() {
        User buyer = register();
        attempt(buyer, PurchaseStatus.FAILED, "stripe", "card_declined", TODAY);
        attempt(buyer, PurchaseStatus.FAILED, "stripe", "card_declined", TODAY);
        attempt(buyer, PurchaseStatus.FAILED, "stripe", "insufficient_funds", TODAY);
        attempt(buyer, PurchaseStatus.FAILED, "momo", null, TODAY);

        var health = statsService.paymentHealth(7);

        assertThat(health.failureReasons()).hasSize(3);
        assertThat(health.failureReasons().get(0).reason()).isEqualTo("card_declined");
        assertThat(health.failureReasons().get(0).failures()).isEqualTo(2);
        // A failure nobody recorded a cause for is a category, not a row to drop.
        assertThat(health.failureReasons())
                .anySatisfy(r -> assertThat(r.reason()).isEqualTo("Not stated"));
    }

    @Test
    @DisplayName("A payment pending for a day is flagged; a fresh one is not")
    void stuckPaymentsAreFlagged() {
        User buyer = register();
        attempt(buyer, PurchaseStatus.PENDING, "stripe", null, TODAY);
        // Old enough to be broken rather than slow - the shape of the test-key
        // purchase left pointing at a session the live account cannot see.
        stale(buyer, PurchaseStatus.PENDING, "stripe", Instant.now().minus(50, ChronoUnit.HOURS));

        var health = statsService.paymentHealth(7);

        assertThat(health.stuck().thresholdHours()).isEqualTo(24);
        assertThat(health.stuck().count()).isEqualTo(1);
        assertThat(health.stuck().oldestHours()).isGreaterThanOrEqualTo(49);
    }

    @Test
    @DisplayName("The window is clamped rather than trusted")
    void windowIsClamped() {
        assertThat(statsService.paymentHealth(0).points()).hasSize(1);
        assertThat(statsService.paymentHealth(-5).points()).hasSize(1);
        assertThat(statsService.paymentHealth(10_000).points()).hasSize(StatsService.MAX_DAYS);
    }

    // ------------------------------------------------------------- fixtures

    private void attempt(User buyer, PurchaseStatus status, String provider, String reason, LocalDate on) {
        stale(buyer, status, provider, reason, middleOf(on));
    }

    private void stale(User buyer, PurchaseStatus status, String provider, Instant startedAt) {
        stale(buyer, status, provider, null, startedAt);
    }

    /**
     * One attempt, started at a chosen moment.
     *
     * <p>{@code created_at} is what this dashboard windows on, and it is only
     * defaulted when absent - so setting it before the insert is enough, and
     * nothing has to update an immutable column afterwards.
     *
     * <p>A {@code PROFILE_UNLOCK} for the same reason the revenue tests use one:
     * {@code purchases_shape_check} makes every type carry its own reference, and
     * a target user is the cheapest one to satisfy honestly.
     */
    private void stale(User buyer, PurchaseStatus status, String provider, String reason, Instant startedAt) {
        Purchase purchase = Purchase.builder()
                .user(buyer)
                .targetUser(creator())
                .type(PurchaseType.PROFILE_UNLOCK)
                .amountMinor(2_000)
                .currency("XAF")
                .provider(provider)
                .status(status)
                .failureReason(reason)
                .completedAt(status == PurchaseStatus.COMPLETED ? startedAt : null)
                .build();
        purchase.setCreatedAt(startedAt);
        purchaseRepository.save(purchase);
    }

    /** Midday UTC, so a bucket cannot be nudged across a boundary by an hour. */
    private Instant middleOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plus(12, ChronoUnit.HOURS).toInstant();
    }

    private User creator() {
        if (creator == null) creator = register();
        return creator;
    }

    private User register() {
        String email = "pay-" + UUID.randomUUID() + "@example.com";
        var auth = authService.register(
                new RegisterRequest(email, "correct-horse-9", AccountType.VIEWER, null), null);
        return userRepository.findById(auth.auth().userId()).orElseThrow();
    }
}
