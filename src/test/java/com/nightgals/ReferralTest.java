package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.referral.CreditService;
import com.nightgals.referral.ReferralService;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invite codes, and the credit they pay out.
 *
 * <p>The bonus lands on the referred account's <em>first</em> package - not on
 * their signup, and not on their second package.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "nightgals.creator-packages.enabled=true")
@Transactional
class ReferralTest {

    @Autowired AuthService authService;
    @Autowired BillingService billingService;
    @Autowired ReferralService referralService;
    @Autowired CreditService creditService;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("Every account gets a code, and it is shareable as a link")
    void everyAccountHasACode() {
        User user = register(null);
        var summary = referralService.summaryFor(user);

        assertThat(summary.code()).hasSize(8).matches("[BCDFGHJKMNPQRSTVWXYZ23456789]+");
        assertThat(summary.shareLink()).endsWith("/join?ref=" + summary.code());
        assertThat(summary.bonusPerReferralMinor()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("The bonus lands on the referred account's first package, not on their signup")
    void bonusOnFirstPurchase() {
        User referrer = register(null);
        User invited = register(referrer.getReferralCode());

        // Signing up earns nothing - otherwise this is a bot farm.
        assertThat(creditService.balanceOf(referrer.getId())).isZero();
        assertThat(referralService.summaryFor(reload(referrer)).invited()).isEqualTo(1);

        buyPackage(invited);

        assertThat(creditService.balanceOf(referrer.getId())).isEqualTo(5_000L);
        var summary = referralService.summaryFor(reload(referrer));
        assertThat(summary.converted()).isEqualTo(1);
        assertThat(summary.creditBalanceDisplay()).isEqualTo("5000");
    }

    @Test
    @DisplayName("A second package earns the referrer nothing more")
    void bonusIsOncePerAccount() {
        User referrer = register(null);
        User invited = register(referrer.getReferralCode());

        buyPackage(invited);
        buyPackage(reload(invited));

        assertThat(creditService.balanceOf(referrer.getId())).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("An unknown code is ignored rather than refused")
    void unknownCodeIsIgnored() {
        // Somebody mistyping an invite should still end up with an account.
        User user = register("NOTACODE");

        assertThat(user).isNotNull();
        assertThat(userRepository.findReferrerOf(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("Credit is spent on a purchase before any payment provider sees it")
    void creditIsSpentFirst() {
        User referrer = register(null);
        buyPackage(register(referrer.getReferralCode()));
        assertThat(creditService.balanceOf(referrer.getId())).isEqualTo(5_000L);

        // Pro is 5000, so the credit covers it exactly and nothing is charged.
        var checkout = billingService.buyCreatorPackage(reload(referrer), "PRO");

        assertThat(checkout.purchase().status())
                .isEqualTo(com.nightgals.billing.PurchaseStatus.COMPLETED);
        assertThat(checkout.action())
                .isEqualTo(com.nightgals.billing.PaymentProvider.PaymentInstruction.Action.NONE);
        assertThat(creditService.balanceOf(referrer.getId())).isZero();
    }

    @Test
    @DisplayName("Credit covering only part of a price still leaves the rest to pay")
    void partialCredit() {
        User referrer = register(null);
        buyPackage(register(referrer.getReferralCode()));

        // 5000 of credit against a 15000 package pays a third of it.
        var checkout = billingService.buyCreatorPackage(reload(referrer), "BLACK_DIAMOND");

        assertThat(checkout.purchase().status())
                .isEqualTo(com.nightgals.billing.PurchaseStatus.PENDING);
        assertThat(creditService.balanceOf(referrer.getId())).isZero();
    }

    // ------------------------------------------------------------- helpers

    private void buyPackage(User creator) {
        var checkout = billingService.buyCreatorPackage(reload(creator), "BLACK_DIAMOND");
        billingService.settle(checkout.purchase().id(), null);
    }

    private User register(String referralCode) {
        String email = "ref-" + UUID.randomUUID() + "@example.com";
        authService.register(
                new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, referralCode), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
