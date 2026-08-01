package com.nightgals.referral;

import com.nightgals.billing.Purchase;
import com.nightgals.common.Money;
import com.nightgals.common.PageResponse;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.referral.dto.CreditEntryResponse;
import com.nightgals.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Spending referral credit.
 *
 * <p>Credit is not a currency of its own - it is a discount denominated in the
 * platform's currency, applied to a purchase before the payment provider ever
 * sees it. A purchase covered entirely by credit costs nothing to settle, which
 * is why {@link #applyTo} reports back how much is actually left to pay.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditService {

    private final CreditRepository creditRepository;
    private final MonetizationProperties properties;

    /** What a credit application did, so the caller can decide what to charge. */
    public record Applied(long creditUsedMinor, long remainingToPayMinor) {

        public boolean coversEverything() {
            return remainingToPayMinor == 0;
        }
    }

    @Transactional(readOnly = true)
    public long balanceOf(UUID userId) {
        return creditRepository.balanceOf(userId);
    }

    /**
     * Puts as much credit as is available towards a purchase.
     *
     * <p>Partial by design: 5 000 of credit against a 15 000 package pays a third
     * of it rather than being refused for not covering the whole thing.
     *
     * <p>The purchase's own amount is left alone. It records what the thing cost;
     * the ledger records what was paid with credit. Reducing the amount would
     * make the two disagree and quietly halve the creator's earnings.
     */
    @Transactional
    public Applied applyTo(User buyer, Purchase purchase) {
        long price = purchase.getAmountMinor();
        long balance = creditRepository.balanceOf(buyer.getId());

        if (balance <= 0 || price <= 0) {
            return new Applied(0, price);
        }

        long used = Math.min(balance, price);
        creditRepository.save(CreditEntry.builder()
                .user(buyer)
                // Negative: this is credit leaving the account.
                .amountMinor(-used)
                .currency(purchase.getCurrency())
                .reason(CreditReason.SPEND)
                .purchase(purchase)
                .note("Applied to purchase " + purchase.getId())
                .build());

        log.info("{} of credit applied to purchase {} by {}",
                Money.withCurrency(used, purchase.getCurrency()), purchase.getId(), buyer.getId());

        return new Applied(used, price - used);
    }

    /**
     * Puts credit back after a purchase it paid for was reversed.
     *
     * <p>Recorded as a fresh positive entry rather than by deleting the spend:
     * the ledger is append-only, and "spent then refunded" is a different fact
     * from "never spent".
     */
    @Transactional
    public void refund(User user, long amountMinor, Purchase purchase, String note) {
        if (amountMinor <= 0) {
            return;
        }
        creditRepository.save(CreditEntry.builder()
                .user(user)
                .amountMinor(amountMinor)
                .currency(properties.currency())
                .reason(CreditReason.REFUND)
                .purchase(purchase)
                .note(note)
                .build());
    }

    @Transactional(readOnly = true)
    public PageResponse<CreditEntryResponse> history(UUID userId, Pageable pageable) {
        return PageResponse.from(
                creditRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable),
                CreditEntryResponse::of);
    }
}
