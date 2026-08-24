package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.Purchase;
import com.nightgals.billing.PurchaseRepository;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.billing.PurchaseType;
import com.nightgals.stats.StatsService;
import com.nightgals.stats.dto.RevenueResponse;
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
 * The revenue dashboard's arithmetic.
 *
 * <p>The figure a dashboard prints is only worth anything if it is the same
 * money the providers actually moved, so what is pinned here is mostly what
 * must <em>not</em> be counted: unsettled attempts, balances spent twice, and
 * two currencies added together.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class RevenueStatsTest {

    @Autowired StatsService statsService;
    @Autowired PurchaseRepository purchaseRepository;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private User creator;

    @Test
    @DisplayName("Settled purchases land on the day they settled, in their own currency")
    void bucketsByDay() {
        User buyer = register();
        settled(buyer, 10_000, "XAF", TODAY);
        settled(buyer, 5_000, "XAF", TODAY);
        settled(buyer, 3_000, "XAF", TODAY.minusDays(2));

        var xaf = seriesFor(statsService.revenue(7), "XAF");

        assertThat(xaf.cashMinor()).isEqualTo(18_000);
        assertThat(xaf.orders()).isEqualTo(3);
        assertThat(pointOn(xaf, TODAY).cashMinor()).isEqualTo(15_000);
        assertThat(pointOn(xaf, TODAY.minusDays(2)).cashMinor()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("Quiet days are present as zeroes, so the axis stays a calendar")
    void fillsGaps() {
        User buyer = register();
        settled(buyer, 4_000, "XAF", TODAY.minusDays(6));

        var response = statsService.revenue(7);
        var xaf = seriesFor(response, "XAF");

        assertThat(response.from()).isEqualTo(TODAY.minusDays(6));
        assertThat(response.to()).isEqualTo(TODAY);
        assertThat(xaf.points()).hasSize(7);
        // Oldest first, and every day in between accounted for.
        assertThat(xaf.points().getFirst().date()).isEqualTo(TODAY.minusDays(6));
        assertThat(xaf.points().getLast().date()).isEqualTo(TODAY);
        assertThat(pointOn(xaf, TODAY.minusDays(3)).cashMinor()).isZero();
        assertThat(pointOn(xaf, TODAY).orders()).isZero();
    }

    @Test
    @DisplayName("Credit spent is not banked twice: cash is the price less the balance used")
    void creditIsNotCountedAgain() {
        User buyer = register();

        // A 20 000 item, half of it paid from a balance the member topped up
        // earlier. Only the 10 000 that reached a provider is new money.
        Purchase p = purchase(buyer, 20_000, "XAF", TODAY);
        p.setCreditAppliedMinor(10_000);
        purchaseRepository.save(p);

        var xaf = seriesFor(statsService.revenue(7), "XAF");

        assertThat(xaf.cashMinor()).isEqualTo(10_000);
        assertThat(xaf.grossMinor()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("Only settled money counts")
    void ignoresUnsettled() {
        User buyer = register();
        settled(buyer, 9_000, "XAF", TODAY);
        unsettled(buyer, 50_000, "XAF", PurchaseStatus.PENDING);
        unsettled(buyer, 70_000, "XAF", PurchaseStatus.FAILED);
        unsettled(buyer, 80_000, "XAF", PurchaseStatus.CANCELLED);

        var xaf = seriesFor(statsService.revenue(7), "XAF");

        assertThat(xaf.cashMinor()).isEqualTo(9_000);
        assertThat(xaf.orders()).isEqualTo(1);
    }

    @Test
    @DisplayName("Currencies stay apart rather than being added into a meaningless total")
    void currenciesAreSeparate() {
        User buyer = register();
        settled(buyer, 12_000, "XAF", TODAY);
        settled(buyer, 40, "USD", TODAY);

        var response = statsService.revenue(7);

        assertThat(seriesFor(response, "XAF").cashMinor()).isEqualTo(12_000);
        assertThat(seriesFor(response, "USD").cashMinor()).isEqualTo(40);
        // Busiest first, so a client drawing one line draws the one that matters.
        assertThat(response.series().getFirst().currency()).isEqualTo("XAF");
    }

    @Test
    @DisplayName("A payment opened yesterday and confirmed today belongs to today")
    void datedBySettlementNotStart() {
        User buyer = register();
        // createdAt is stamped now by BaseEntity; settlement is backdated.
        settled(buyer, 6_000, "XAF", TODAY.minusDays(1));

        var xaf = seriesFor(statsService.revenue(7), "XAF");

        assertThat(pointOn(xaf, TODAY.minusDays(1)).cashMinor()).isEqualTo(6_000);
        assertThat(pointOn(xaf, TODAY).cashMinor()).isZero();
    }

    @Test
    @DisplayName("The window is clamped, so no request can scan the whole table")
    void clampsWindow() {
        assertThat(statsService.revenue(0).from()).isEqualTo(TODAY);
        assertThat(statsService.revenue(-5).from()).isEqualTo(TODAY);
        assertThat(statsService.revenue(10_000).from())
                .isEqualTo(TODAY.minusDays(StatsService.MAX_DAYS - 1L));
    }

    @Test
    @DisplayName("No takings at all is an empty list, not a fabricated zero series")
    void quietWindow() {
        assertThat(statsService.revenue(7).series()).isEmpty();
    }

    // ------------------------------------------------------------- helpers

    private RevenueResponse.RevenueSeries seriesFor(RevenueResponse response, String currency) {
        return response.series().stream()
                .filter(s -> s.currency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + currency + " series in " + response.series()));
    }

    private RevenueResponse.RevenuePoint pointOn(RevenueResponse.RevenueSeries series, LocalDate date) {
        return series.points().stream()
                .filter(p -> p.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No point for " + date));
    }

    private void settled(User buyer, long amountMinor, String currency, LocalDate on) {
        purchaseRepository.save(purchase(buyer, amountMinor, currency, on));
    }

    /**
     * A settled sale.
     *
     * <p>Built as a {@code PROFILE_UNLOCK} because {@code purchases_shape_check}
     * requires every type to carry its own reference - a media unlock without a
     * {@code media_id} is rejected by the database - and a target user is the
     * cheapest reference to satisfy honestly.
     */
    private Purchase purchase(User buyer, long amountMinor, String currency, LocalDate on) {
        return Purchase.builder()
                .user(buyer)
                .targetUser(creator())
                .type(PurchaseType.PROFILE_UNLOCK)
                .amountMinor(amountMinor)
                .currency(currency)
                .provider("manual")
                .status(PurchaseStatus.COMPLETED)
                .completedAt(middleOf(on))
                .build();
    }

    private void unsettled(User buyer, long amountMinor, String currency, PurchaseStatus status) {
        purchaseRepository.save(Purchase.builder()
                .user(buyer)
                .targetUser(creator())
                .type(PurchaseType.PROFILE_UNLOCK)
                .amountMinor(amountMinor)
                .currency(currency)
                .provider("manual")
                .status(status)
                .build());
    }

    /** Midday UTC, so a bucket cannot be nudged across a boundary by an hour. */
    private Instant middleOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).plus(12, ChronoUnit.HOURS).toInstant();
    }

    /** The creator being bought, reused across a test's rows. */
    private User creator() {
        if (creator == null) creator = register();
        return creator;
    }

    private User register() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.VIEWER, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }
}
