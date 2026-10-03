package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.referral.ReferralService;
import com.nightgals.stats.StatsService;
import com.nightgals.stats.dto.ReferralStatsResponse;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The referrals dashboard.
 *
 * <p>The thing it must never do is disagree with a referrer's own page, so the
 * counted figure is checked against {@link ReferralService#summaryFor} rather
 * than against a number written out here.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@TestPropertySource(properties = "nightgals.creator-packages.enabled=true")
class ReferralStatsTest {

    @Autowired StatsService statsService;
    @Autowired AuthService authService;
    @Autowired BillingService billingService;
    @Autowired ProfileService profileService;
    @Autowired ReferralService referralService;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Test
    @DisplayName("A referrer's sign-ups are split into counted, pending and viewers")
    void splitsSignups() {
        User referrer = register(null, AccountType.CREATOR);
        completeProfile(register(referrer.getReferralCode(), AccountType.CREATOR));
        register(referrer.getReferralCode(), AccountType.CREATOR);
        register(referrer.getReferralCode(), AccountType.VIEWER);
        // Somebody who came on their own must not show up anywhere.
        completeProfile(register(null, AccountType.CREATOR));

        var stats = flushAndLoad(7);
        var row = rowFor(stats, referrer);

        assertThat(row.signups()).isEqualTo(3);
        assertThat(row.counted()).isEqualTo(1);
        assertThat(row.pending()).isEqualTo(1);
        assertThat(row.viewers()).isEqualTo(1);
        assertThat(row.code()).isEqualTo(referrer.getReferralCode());
        assertThat(row.email()).isEqualTo(referrer.getEmail());
        assertThat(row.lastSignupAt()).isNotNull();

        assertThat(stats.signups()).isEqualTo(3);
        assertThat(stats.counted()).isEqualTo(1);
        assertThat(stats.pending()).isEqualTo(1);
        assertThat(stats.viewers()).isEqualTo(1);
    }

    @Test
    @DisplayName("The counted figure matches what the referrer sees on their own page")
    void agreesWithReferrersOwnPage() {
        User referrer = register(null, AccountType.CREATOR);
        completeProfile(register(referrer.getReferralCode(), AccountType.CREATOR));
        completeProfile(register(referrer.getReferralCode(), AccountType.CREATOR));
        register(referrer.getReferralCode(), AccountType.CREATOR);

        var row = rowFor(flushAndLoad(30), referrer);
        long ownPage = referralService.summaryFor(reload(referrer)).invited();

        assertThat(row.allTimeCounted()).isEqualTo(ownPage).isEqualTo(2);
    }

    @Test
    @DisplayName("A paid bonus shows as a conversion, with the credit it paid out")
    void showsPaidBonuses() {
        User referrer = register(null, AccountType.CREATOR);
        User invited = register(referrer.getReferralCode(), AccountType.CREATOR);
        completeProfile(invited);

        var checkout = billingService.buyCreatorPackage(reload(invited), "BLACK_DIAMOND");
        billingService.settle(checkout.purchase().id(), null);

        var stats = flushAndLoad(7);
        var row = rowFor(stats, referrer);

        assertThat(row.converted()).isEqualTo(1);
        assertThat(row.creditPaidMinor()).isEqualTo(5_000L);
        assertThat(stats.converted()).isEqualTo(1);
        assertThat(stats.creditPaidMinor()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("Sign-ups from before the window stay out of it, but the referrer and their all-time figure remain")
    void windowMovesTheSignupsOnly() {
        User referrer = register(null, AccountType.CREATOR);
        User old = register(referrer.getReferralCode(), AccountType.CREATOR);
        completeProfile(old);
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE users SET created_at = now() - interval '40 days' WHERE id = :id")
                .setParameter("id", old.getId())
                .executeUpdate();

        var week = flushAndLoad(7);
        var row = rowFor(week, referrer);

        assertThat(row.signups()).isZero();
        assertThat(row.counted()).isZero();
        assertThat(row.allTimeCounted()).isEqualTo(1);
        assertThat(week.signups()).isZero();

        assertThat(rowFor(flushAndLoad(90), referrer).counted()).isEqualTo(1);
    }

    @Test
    @DisplayName("Quiet days are present as zeroes, and today carries the sign-ups")
    void dailySeries() {
        User referrer = register(null, AccountType.CREATOR);
        completeProfile(register(referrer.getReferralCode(), AccountType.CREATOR));
        register(referrer.getReferralCode(), AccountType.VIEWER);

        var stats = flushAndLoad(7);

        assertThat(stats.from()).isEqualTo(TODAY.minusDays(6));
        assertThat(stats.to()).isEqualTo(TODAY);
        assertThat(stats.points()).hasSize(7);
        var today = stats.points().get(6);
        assertThat(today.date()).isEqualTo(TODAY);
        assertThat(today.signups()).isEqualTo(2);
        assertThat(today.counted()).isEqualTo(1);
        assertThat(stats.points().get(0).signups()).isZero();
    }

    // ------------------------------------------------------------- helpers

    /** Native queries do not see unflushed entities, so everything is written out first. */
    private ReferralStatsResponse flushAndLoad(int days) {
        entityManager.flush();
        return statsService.referrals(days);
    }

    private ReferralStatsResponse.Referrer rowFor(ReferralStatsResponse stats, User referrer) {
        return stats.referrers().stream()
                .filter(r -> r.userId().equals(referrer.getId()))
                .findFirst()
                .orElseThrow();
    }

    private User register(String referralCode, AccountType type) {
        String email = "refstat-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", type, referralCode), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private void completeProfile(User user) {
        profileService.createOrUpdate(reload(user), new ProfileRequest(
                null, "Here for the weekend", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
