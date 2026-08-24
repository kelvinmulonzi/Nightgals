package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.PaymentChoice;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.earnings.EarningRepository;
import com.nightgals.earnings.EarningType;
import com.nightgals.live.GiftService;
import com.nightgals.live.LiveSessionService;
import com.nightgals.live.dto.LiveSessionRequest;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.referral.CreditService;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gifts, and the balance they are sent from.
 *
 * <p>The money question underneath all of it is whether balance can be conjured.
 * Two ways it could be: paying for a top-up with a top-up, and a creator gifting
 * her own broadcast to turn bought balance into withdrawable earnings. Both are
 * tested here, because neither is visible until the numbers are already wrong.
 *
 * <p>{@code auto} stands in for a real provider so a top-up settles inside the
 * call - what is being tested is what settlement does to the ledger, not how any
 * particular processor gets there.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.monetization.providers=auto",
        "nightgals.monetization.default-provider=auto",
        "nightgals.creator-packages.enabled=false",
        // Otherwise a brand-new account is on trial and the paywall never applies.
        "nightgals.monetization.free-trial=PT0S",
})
@Transactional
class GiftTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired BillingService billingService;
    @Autowired LiveSessionService liveSessionService;
    @Autowired GiftService giftService;
    @Autowired CreditService creditService;
    @Autowired UserRepository userRepository;
    @Autowired EarningRepository earningRepository;

    // ------------------------------------------------------- live is ticketed

    @Test
    @DisplayName("Every broadcast is ticketed at the price its host set")
    void broadcastsAreTicketed() {
        User creator = approvedCreator();

        var session = liveSessionService.create(creator, new LiveSessionRequest(
                "Friday set", "https://stream.example.com/a", null, null, null, 3_000L));

        assertThat(session.tier()).isEqualTo(com.nightgals.media.ContentTier.EXCLUSIVE);
        assertThat(session.priceMinor()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("A broadcast with no price is refused rather than given a default")
    void aPriceIsRequired() {
        User creator = approvedCreator();

        // The alternative is worse than an error: she announces a show, viewers
        // are charged the platform's number, and the first she hears of it is her
        // earnings report.
        assertThatThrownBy(() -> liveSessionService.create(creator, new LiveSessionRequest(
                "Friday set", "https://stream.example.com/a", null, null, null, null)))
                .hasMessageContaining("Set what viewers pay");
    }

    @Test
    @DisplayName("Asking for a free broadcast is refused, not quietly charged")
    void freeBroadcastsCannotBeCreated() {
        User creator = approvedCreator();

        assertThatThrownBy(() -> liveSessionService.create(creator, new LiveSessionRequest(
                "Friday set", null, null, null,
                com.nightgals.media.ContentTier.FREE, 3_000L)))
                .hasMessageContaining("paid to join");
    }

    // ------------------------------------------------------------- top-ups

    @Test
    @DisplayName("A settled top-up credits the balance")
    void topUpCreditsBalance() {
        User viewer = viewer();

        var checkout = billingService.buyCredit(viewer, 5000L, PaymentChoice.none());

        assertThat(checkout.purchase().status()).isEqualTo(PurchaseStatus.COMPLETED);
        assertThat(creditService.balanceOf(viewer.getId())).isEqualTo(5000L);
    }

    @Test
    @DisplayName("Balance is never spent on buying balance, which would be free money")
    void topUpDoesNotConsumeExistingBalance() {
        User viewer = viewer();
        billingService.buyCredit(viewer, 5000L, PaymentChoice.none());

        // With credit applied to top-ups, this second one would be paid for by the
        // first - settling for nothing and handing back what it consumed.
        billingService.buyCredit(reload(viewer), 5000L, PaymentChoice.none());

        assertThat(creditService.balanceOf(viewer.getId())).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("A top-up outside the configured bounds is refused")
    void topUpBoundsAreEnforced() {
        User viewer = viewer();

        assertThatThrownBy(() -> billingService.buyCredit(viewer, 1L, PaymentChoice.none()))
                .hasMessageContaining("Top up between");
        assertThatThrownBy(() ->
                billingService.buyCredit(reload(viewer), 99_999_999L, PaymentChoice.none()))
                .hasMessageContaining("Top up between");
    }

    // --------------------------------------------------------------- gifts

    @Test
    @DisplayName("Sending a gift debits the sender and earns the creator her share")
    void giftMovesMoney() {
        User creator = approvedCreator();
        UUID sessionId = liveSession(creator);
        User viewer = toppedUpViewer(10_000L);

        var gift = giftService.send(viewer, sessionId, "ROSE", "keep going!");

        assertThat(gift.giftCode()).isEqualTo("ROSE");
        assertThat(gift.amountMinor()).isEqualTo(500L);
        assertThat(gift.message()).isEqualTo("keep going!");

        assertThat(creditService.balanceOf(viewer.getId())).isEqualTo(9_500L);

        var earnings = earningRepository.findAll().stream()
                .filter(e -> e.getType() == EarningType.GIFT)
                .filter(e -> e.getCreator().getId().equals(creator.getId()))
                .toList();
        assertThat(earnings).hasSize(1);
        assertThat(earnings.getFirst().getGrossMinor()).isEqualTo(500L);
        // 30% commission, so the creator keeps 350 of a 500 rose.
        assertThat(earnings.getFirst().getNetMinor()).isEqualTo(350L);
        // Held, not payable on the spot - the card behind the balance can still
        // be charged back.
        assertThat(earnings.getFirst().getPurchase()).isNull();
    }

    @Test
    @DisplayName("A gift beyond the balance is refused and takes nothing")
    void insufficientBalanceIsRefused() {
        User creator = approvedCreator();
        UUID sessionId = liveSession(creator);
        User viewer = toppedUpViewer(1000L);

        assertThatThrownBy(() -> giftService.send(viewer, sessionId, "CROWN", null))
                .hasMessageContaining("Top up to continue");

        assertThat(creditService.balanceOf(viewer.getId())).isEqualTo(1000L);
        assertThat(earningRepository.findAll().stream()
                .noneMatch(e -> e.getType() == EarningType.GIFT)).isTrue();
    }

    @Test
    @DisplayName("A creator cannot gift her own broadcast, which would cash out a card")
    void selfGiftIsRefused() {
        User creator = approvedCreator();
        UUID sessionId = liveSession(creator);
        billingService.buyCredit(reload(creator), 5000L, PaymentChoice.none());

        assertThatThrownBy(() ->
                giftService.send(reload(creator), sessionId, "ROSE", null))
                .hasMessageContaining("cannot send a gift to yourself");
    }

    @Test
    @DisplayName("Gifts are refused once the broadcast is over")
    void endedBroadcastsRefuseGifts() {
        User creator = approvedCreator();
        UUID sessionId = liveSession(creator);
        liveSessionService.end(reload(creator), sessionId);
        User viewer = toppedUpViewer(10_000L);

        assertThatThrownBy(() -> giftService.send(viewer, sessionId, "ROSE", null))
                .hasMessageContaining("not live");
    }

    @Test
    @DisplayName("An unknown gift code is refused rather than charged at some other price")
    void unknownGiftIsRefused() {
        User creator = approvedCreator();
        UUID sessionId = liveSession(creator);
        User viewer = toppedUpViewer(10_000L);

        assertThatThrownBy(() -> giftService.send(viewer, sessionId, "YACHT", null))
                .hasMessageContaining("No such gift");

        assertThat(creditService.balanceOf(viewer.getId())).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("Gift codes are case-insensitive, so clients need not shout")
    void giftCodeIsCaseInsensitive() {
        User creator = approvedCreator();
        UUID sessionId = liveSession(creator);
        User viewer = toppedUpViewer(10_000L);

        assertThat(giftService.send(viewer, sessionId, "rose", null).giftCode())
                .isEqualTo("ROSE");
    }

    // ---------------------------------------------------------------- feed

    @Test
    @DisplayName("The feed returns what was sent, with a running total")
    void feedReportsGiftsAndTotal() {
        User creator = approvedCreator();
        UUID sessionId = liveSession(creator);
        User viewer = toppedUpViewer(10_000L);

        giftService.send(viewer, sessionId, "ROSE", null);
        giftService.send(reload(viewer), sessionId, "HEART", null);

        var feed = giftService.feed(sessionId, null);

        assertThat(feed.gifts()).hasSize(2);
        // Oldest first: this is a feed being caught up with, not a listing.
        assertThat(feed.gifts()).extracting("giftCode").containsExactly("ROSE", "HEART");
        assertThat(feed.totalMinor()).isEqualTo(1500L);
        assertThat(feed.until()).isNotNull();
    }

    @Test
    @DisplayName("Polling from the last cursor does not replay gifts already seen")
    void feedSinceExcludesWhatWasAlreadyDelivered() {
        User creator = approvedCreator();
        UUID sessionId = liveSession(creator);
        User viewer = toppedUpViewer(10_000L);
        giftService.send(viewer, sessionId, "ROSE", null);

        var first = giftService.feed(sessionId, null);
        assertThat(first.gifts()).hasSize(1);

        // Nothing new since. A duplicate here is the same gift animating twice.
        var second = giftService.feed(sessionId, first.until());
        assertThat(second.gifts()).isEmpty();
        // The total is a running one, so it stands even when the page is empty.
        assertThat(second.totalMinor()).isEqualTo(500L);
    }

    @Test
    @DisplayName("The catalogue is offered cheapest first")
    void catalogueIsSortedByPrice() {
        var catalogue = giftService.catalogue();

        assertThat(catalogue).isNotEmpty();
        assertThat(catalogue).extracting("priceMinor").isSorted();
        assertThat(catalogue).extracting("icon").doesNotContainNull();
    }

    // ------------------------------------------------------------- helpers

    private User approvedCreator() {
        String email = "creator-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Here for the weekend", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));

        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        userRepository.saveAndFlush(managed);
        return reload(managed);
    }

    private User viewer() {
        String email = "viewer-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.VIEWER, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User toppedUpViewer(long amountMinor) {
        User viewer = viewer();
        billingService.buyCredit(viewer, amountMinor, PaymentChoice.none());
        return reload(viewer);
    }

    /** A broadcast that is on air, since gifts are refused to anything else. */
    private UUID liveSession(User creator) {
        // Null scheduledFor means "start now", which is what puts it LIVE. The
        // price is required on every broadcast now; gifts are what a viewer
        // spends once inside, on top of what they paid at the door.
        return liveSessionService.create(reload(creator), new LiveSessionRequest(
                "Friday set", "https://stream.example.com/a", null, null, null, 3_000L)).id();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
