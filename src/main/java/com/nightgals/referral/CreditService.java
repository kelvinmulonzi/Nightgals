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
     * Adds balance bought with real money.
     *
     * <p>Called only from settlement, never from checkout: crediting on the
     * strength of a PENDING purchase would hand out spendable balance for a
     * card that has not cleared, and a gift sent from it cannot be taken back
     * off the creator once it is on screen.
     *
     * <p>Idempotent per purchase, because settlement is not: a webhook and the
     * reconciliation sweep can both land on the same top-up, and a second entry
     * would silently double somebody's money.
     */
    @Transactional
    public void topUp(User user, Purchase purchase) {
        long amount = purchase.getAmountMinor();
        if (amount <= 0) {
            return;
        }
        if (creditRepository.existsByPurchaseIdAndReason(purchase.getId(), CreditReason.TOPUP)) {
            log.debug("Top-up for purchase {} already credited", purchase.getId());
            return;
        }
        creditRepository.save(CreditEntry.builder()
                .user(user)
                .amountMinor(amount)
                .currency(purchase.getCurrency())
                .reason(CreditReason.TOPUP)
                .purchase(purchase)
                .note("Balance top-up")
                .build());

        log.info("{} credited to {} from top-up {}",
                Money.withCurrency(amount, purchase.getCurrency()), user.getId(), purchase.getId());
    }

    /**
     * Takes credit for something bought outright with balance rather than at
     * checkout - a gift, which has no purchase behind it.
     *
     * @throws com.nightgals.common.ApiException 409 {@code insufficient_credit}
     *         when the balance will not cover it. Deliberately all-or-nothing:
     *         a partly-paid gift is not a thing, unlike a partly-paid purchase.
     */
    @Transactional
    public void spend(User user, long amountMinor, String note) {
        if (amountMinor <= 0) {
            throw com.nightgals.common.ApiException.badRequest(
                    "invalid_amount", "Amount must be positive");
        }
        long balance = creditRepository.balanceOf(user.getId());
        if (balance < amountMinor) {
            throw com.nightgals.common.ApiException.conflict("insufficient_credit",
                    "Your balance is " + Money.withCurrency(balance, properties.currency())
                    + ". Top up to continue.");
        }
        creditRepository.save(CreditEntry.builder()
                .user(user)
                // Negative: this is credit leaving the account.
                .amountMinor(-amountMinor)
                .currency(properties.currency())
                .reason(CreditReason.SPEND)
                .note(note)
                .build());
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
