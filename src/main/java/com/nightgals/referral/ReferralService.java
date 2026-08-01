package com.nightgals.referral;

import com.nightgals.billing.Purchase;
import com.nightgals.billing.PurchaseRepository;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.billing.PurchaseType;
import com.nightgals.common.ApiException;
import com.nightgals.common.Money;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.config.NotificationProperties;
import com.nightgals.mail.EmailService;
import com.nightgals.referral.dto.ReferralSummaryResponse;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Invite codes, and the credit they pay out.
 *
 * <p>The bonus lands when the invited account buys its <em>first</em> package -
 * not when it registers. Paying on signup would make the programme a bot farm;
 * paying on first purchase means the referrer is rewarded for bringing somebody
 * who actually spends.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    private final UserRepository userRepository;
    private final CreditRepository creditRepository;
    private final PurchaseRepository purchaseRepository;
    private final MonetizationProperties monetization;
    private final NotificationProperties notifications;
    private final EmailService emailService;

    // ---------------------------------------------------------------- codes

    /** A code no existing account holds. */
    public String generateUniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = ReferralCodes.random();
            if (!userRepository.existsByReferralCodeIgnoreCase(code)) {
                return code;
            }
        }
        // 28^8 is ~3.8e11, so ten collisions in a row means something is wrong
        // with the generator rather than with luck.
        throw new IllegalStateException("Could not generate a unique referral code");
    }

    /**
     * Resolves a code typed or pasted by a new user.
     *
     * <p>An unknown code is ignored rather than rejected: somebody mistyping an
     * invite should still end up with an account. It is a bonus, not a gate.
     */
    @Transactional(readOnly = true)
    public Optional<User> resolve(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Optional<User> referrer = userRepository.findByReferralCodeIgnoreCase(code.trim());
        if (referrer.isEmpty()) {
            log.debug("Referral code '{}' matched no account; ignoring", code);
        }
        return referrer;
    }

    // ---------------------------------------------------------------- payout

    /**
     * Pays the referrer, if this purchase is the referred account's first package.
     *
     * <p>Called from settlement. Every condition here is a way this can legitimately
     * be a no-op, which is why none of them throw.
     */
    @Transactional
    public void onPurchaseSettled(Purchase purchase) {
        if (purchase.getType() != PurchaseType.CREATOR_PACKAGE
                || purchase.getStatus() != PurchaseStatus.COMPLETED) {
            return;
        }

        // Reloaded rather than read off the purchase: the buyer may be the
        // detached principal from the auth filter, whose lazy associations have
        // no session behind them.
        User buyer = userRepository.findById(purchase.getUser().getId()).orElse(null);
        if (buyer == null) {
            return;
        }
        User referrer = userRepository.findReferrerOf(buyer.getId()).orElse(null);
        if (referrer == null) {
            return;
        }
        if (creditRepository.existsByReferredUserIdAndReason(buyer.getId(), CreditReason.REFERRAL_BONUS)) {
            return;
        }
        // "First subscription" means exactly that: a second package earns nothing.
        if (purchaseRepository.countSettledPackages(buyer.getId()) > 1) {
            return;
        }

        long bonus = monetization.referral() == null ? 0L : monetization.referral().bonusMinor();
        if (bonus <= 0) {
            return;
        }

        try {
            creditRepository.save(CreditEntry.builder()
                    .user(referrer)
                    .amountMinor(bonus)
                    .currency(monetization.currency())
                    .reason(CreditReason.REFERRAL_BONUS)
                    .referredUser(buyer)
                    .note("Referral bonus for " + buyer.getUsername())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Two settlements raced. The unique index held; nothing more to do.
            log.debug("Referral bonus for {} already awarded", buyer.getId());
            return;
        }

        log.info("Referral bonus of {} credited to {} for {}",
                Money.withCurrency(bonus, monetization.currency()), referrer.getId(), buyer.getId());

        emailService.sendReferralBonus(
                referrer.getEmail(),
                referrer.getUsername(),
                buyer.getUsername(),
                Money.withCurrency(bonus, monetization.currency()));
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public ReferralSummaryResponse summaryFor(User user) {
        String currency = monetization.currency();
        long bonus = monetization.referral() == null ? 0L : monetization.referral().bonusMinor();
        long balance = creditRepository.balanceOf(user.getId());

        return new ReferralSummaryResponse(
                user.getReferralCode(),
                shareLink(user.getReferralCode()),
                userRepository.countByReferredById(user.getId()),
                // Only conversions earned anything, so the two numbers are shown
                // separately rather than one flattering total.
                creditRepository.countByUserIdAndReason(user.getId(), CreditReason.REFERRAL_BONUS),
                balance,
                Money.plain(balance, currency),
                bonus,
                Money.plain(bonus, currency),
                currency);
    }

    public String shareLink(String code) {
        String base = notifications.appBaseUrl() == null ? "" : notifications.appBaseUrl();
        return base + "/join?ref=" + code;
    }

    // ---------------------------------------------------------------- internals

}
