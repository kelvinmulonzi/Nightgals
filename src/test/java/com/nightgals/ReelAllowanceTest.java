package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.CreatorPackageCode;
import com.nightgals.billing.CreatorPackageService;
import com.nightgals.common.ApiException;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.reels.ReelService;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How much of the landing page one creator may hold.
 *
 * <p>The reel strip is the only shared surface on the site: a creator's photos
 * and videos sit on her own profile, where more of hers costs nobody else
 * anything, but a reel sits on the front page next to everyone's. It used to be
 * capped at a flat three for all comers, which made it the one place a Pro
 * creator got exactly as much visibility as somebody paying three times as much
 * for it - the thing the tiers exist to sell.
 *
 * <p>Black Diamond 3, Diamond 2, Pro 1.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.monetization.enabled=true",
        "nightgals.monetization.free-trial=P7D",
        "nightgals.creator-packages.enabled=true",
})
@Transactional
class ReelAllowanceTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired BillingService billingService;
    @Autowired CreatorPackageService packageService;
    @Autowired ReelService reelService;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("Each package carries its own number of reels")
    void allowanceFollowsThePackage() {
        assertThat(allowanceOn(CreatorPackageCode.BLACK_DIAMOND)).isEqualTo(3);
        assertThat(allowanceOn(CreatorPackageCode.DIAMOND)).isEqualTo(2);
        assertThat(allowanceOn(CreatorPackageCode.PRO)).isEqualTo(1);
    }

    @Test
    @DisplayName("Pro stops at one, and says which package it is")
    void proStopsAtOne() {
        User pro = approvedCreator();
        buy(pro, CreatorPackageCode.PRO);

        reelService.post(reload(pro), clip(), "tonight");

        assertThatThrownBy(() -> reelService.post(reload(pro), clip(), "and again"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("1 reel")
                .hasMessageContaining("move up a package");
    }

    @Test
    @DisplayName("Black Diamond gets three, and stops at the fourth")
    void blackDiamondGetsThree() {
        User black = approvedCreator();
        buy(black, CreatorPackageCode.BLACK_DIAMOND);

        for (int i = 0; i < 3; i++) {
            reelService.post(reload(black), clip(), "clip " + i);
        }

        assertThatThrownBy(() -> reelService.post(reload(black), clip(), "one too many"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("3 reels");
    }

    @Test
    @DisplayName("A trial creator is metered too, at the entry allowance")
    void theTrialIsMeteredAsWell() {
        // Unlike photos and video, which the trial leaves unmetered. The strip is
        // shared, and "post as many as you like for seven days" is exactly the
        // complaint this limit answers - two thirds of the site is on a trial at
        // any moment, so leaving it out would have changed nothing in practice.
        User trial = approvedCreator();
        assertThat(trial.isOnTrial()).isTrue();
        assertThat(packageService.reelAllowanceFor(trial)).isEqualTo(1);

        reelService.post(reload(trial), clip(), "first");

        assertThatThrownBy(() -> reelService.post(reload(trial), clip(), "second"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("1 reel");
    }

    @Test
    @DisplayName("Taking one down frees the slot")
    void deletingFreesASlot() {
        User pro = approvedCreator();
        buy(pro, CreatorPackageCode.PRO);

        UUID first = reelService.post(reload(pro), clip(), "first").id();
        reelService.remove(first, reload(pro));

        // The cap is on what is showing, not on how many may ever be posted.
        reelService.post(reload(pro), clip(), "second");
        assertThat(reelService.mine(pro.getId())).hasSize(1);
    }

    // ------------------------------------------------------------- helpers

    private int allowanceOn(CreatorPackageCode code) {
        User creator = approvedCreator();
        buy(creator, code);
        return packageService.reelAllowanceFor(reload(creator));
    }

    private User buy(User creator, CreatorPackageCode code) {
        var checkout = billingService.buyCreatorPackage(reload(creator), code.name());
        billingService.settle(checkout.purchase().id(), null);
        return reload(creator);
    }

    private User approvedCreator() {
        String email = "reels-" + UUID.randomUUID() + "@example.com";
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

    private MultipartFile clip() {
        return new MockMultipartFile("file", "r.mp4", "video/mp4", new byte[] {1, 2, 3});
    }
}
