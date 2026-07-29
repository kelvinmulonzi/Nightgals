package com.nightgals.earnings;

import com.nightgals.billing.Purchase;
import com.nightgals.billing.PurchaseRepository;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.billing.PurchaseType;
import com.nightgals.common.ApiException;
import com.nightgals.config.EarningsProperties;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Turning settled purchases into creator earnings.
 *
 * <p>Two attribution rules:
 * <ul>
 *   <li><b>Unlocks</b> are exact - a viewer paid for one creator, so that creator
 *       earns the net of that payment.
 *   <li><b>Subscriptions</b> use user-centric attribution: each subscriber's
 *       payment is divided among the creators <em>that subscriber viewed</em> in
 *       the period. A creator's earnings therefore track their own audience,
 *       rather than their share of one platform-wide pool.
 * </ul>
 *
 * <p>Every entry lands as PENDING and becomes payable only after the hold period,
 * so a refund has a window in which to reverse it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsService {

    private final EarningRepository earningRepository;
    private final PremiumViewRepository premiumViewRepository;
    private final PurchaseRepository purchaseRepository;
    private final UserRepository userRepository;
    private final EarningsProperties properties;

    // ------------------------------------------------------------ attribution

    /**
     * Credits a creator for a settled unlock.
     *
     * <p>Guarded by a unique index on the purchase, so replaying settlement
     * cannot pay twice.
     */
    @Transactional
    public void recordUnlockEarning(Purchase purchase) {
        if (purchase.getType() != PurchaseType.PROFILE_UNLOCK
                || purchase.getStatus() != PurchaseStatus.COMPLETED) {
            return;
        }
        if (earningRepository.existsForPurchase(purchase.getId(), EarningType.UNLOCK)) {
            return;
        }

        Split split = split(purchase.getAmountMinor());
        Instant availableAt = Instant.now().plus(properties.holdPeriod());
        earningRepository.save(Earning.builder()
                .creator(purchase.getTargetUser())
                .type(EarningType.UNLOCK)
                .purchase(purchase)
                .grossMinor(split.gross())
                .commissionMinor(split.commission())
                .netMinor(split.net())
                .currency(purchase.getCurrency())
                .status(statusFor(availableAt))
                .availableAt(availableAt)
                .build());

        log.info("Creator {} earned {} minor units from unlock purchase {}",
                purchase.getTargetUser().getId(), split.net(), purchase.getId());
    }

    /**
     * Notes that a subscriber consumed a creator's paid content, so the creator
     * takes a share of that subscriber's payment for the period.
     *
     * <p>Called on every premium read; the unique index makes repeats free.
     */
    @Transactional
    public void recordPremiumView(User viewer, UUID creatorId) {
        if (viewer.getId().equals(creatorId) || viewer.isStaff()) {
            return;
        }
        String period = currentPeriod();
        if (premiumViewRepository.existsByViewerIdAndCreatorIdAndPeriod(viewer.getId(), creatorId, period)) {
            return;
        }
        userRepository.findById(creatorId).ifPresent(creator ->
                premiumViewRepository.save(PremiumView.builder()
                        .viewer(viewer)
                        .creator(creator)
                        .period(period)
                        .build()));
    }

    /**
     * Splits every subscription payment settled in a period among the creators
     * that subscriber actually viewed.
     *
     * <p>Idempotent per (purchase, creator, period): re-running a distribution
     * tops up creators newly viewed since the last run rather than paying the
     * same share again.
     *
     * @return how many earning entries were created
     */
    @Transactional
    public int distributeSubscriptionRevenue(String period) {
        YearMonth month = parsePeriod(period);
        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Purchase> subscriptions = purchaseRepository.findAll().stream()
                .filter(p -> p.getType() == PurchaseType.SUBSCRIPTION)
                .filter(p -> p.getStatus() == PurchaseStatus.COMPLETED)
                .filter(p -> p.getCompletedAt() != null
                        && !p.getCompletedAt().isBefore(from) && p.getCompletedAt().isBefore(to))
                .toList();

        int created = 0;
        for (Purchase purchase : subscriptions) {
            List<UUID> creatorIds =
                    premiumViewRepository.findCreatorIdsViewedBy(purchase.getUser().getId(), period);
            if (creatorIds.isEmpty()) {
                // Nobody was viewed: the platform keeps it. Nothing to attribute.
                continue;
            }

            Split split = split(purchase.getAmountMinor());
            long perCreator = split.net() / creatorIds.size();
            // Integer division loses up to (n-1) minor units; the remainder goes to
            // the first creator rather than evaporating.
            long remainder = split.net() - (perCreator * creatorIds.size());

            for (int i = 0; i < creatorIds.size(); i++) {
                UUID creatorId = creatorIds.get(i);
                long net = perCreator + (i == 0 ? remainder : 0);
                if (net <= 0) {
                    continue;
                }
                if (earningRepository.existsSubscriptionShare(purchase.getId(), creatorId, period)) {
                    continue;
                }

                long gross = grossFor(net);
                User creator = userRepository.findById(creatorId).orElse(null);
                if (creator == null) {
                    continue;
                }
                Instant availableAt = Instant.now().plus(properties.holdPeriod());
                earningRepository.save(Earning.builder()
                        .creator(creator)
                        .type(EarningType.SUBSCRIPTION_SHARE)
                        .purchase(purchase)
                        .period(period)
                        .grossMinor(gross)
                        .commissionMinor(gross - net)
                        .netMinor(net)
                        .currency(purchase.getCurrency())
                        .status(statusFor(availableAt))
                        .availableAt(availableAt)
                        .note("Share of subscription " + purchase.getId() + " for " + period)
                        .build());
                created++;
            }
        }

        log.info("Distributed subscription revenue for {}: {} entries across {} subscriptions",
                period, created, subscriptions.size());
        return created;
    }

    /** Manual credit or debit. Negative amounts are allowed, for clawbacks. */
    @Transactional
    public Earning adjust(UUID creatorId, long netMinor, String note, String currency) {
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> ApiException.notFound("Creator"));
        if (note == null || note.isBlank()) {
            throw ApiException.badRequest("note_required", "An adjustment must say why");
        }
        return earningRepository.save(Earning.builder()
                .creator(creator)
                .type(EarningType.ADJUSTMENT)
                .grossMinor(netMinor)
                .commissionMinor(0)
                .netMinor(netMinor)
                .currency(currency)
                // Adjustments are immediately payable; they are already a decision.
                .status(EarningStatus.AVAILABLE)
                .availableAt(Instant.now())
                .note(note)
                .build());
    }

    /** Reverses everything a purchase earned, e.g. after a refund. */
    @Transactional
    public int reverseForPurchase(UUID purchaseId) {
        List<Earning> entries = earningRepository.findAll().stream()
                .filter(e -> e.getPurchase() != null && e.getPurchase().getId().equals(purchaseId))
                .filter(e -> e.getStatus() == EarningStatus.PENDING
                        || e.getStatus() == EarningStatus.AVAILABLE)
                .toList();
        entries.forEach(e -> {
            e.setStatus(EarningStatus.REVERSED);
            e.setNote("Reversed: purchase " + purchaseId + " refunded");
        });
        if (!entries.isEmpty()) {
            log.info("Reversed {} earning entries for purchase {}", entries.size(), purchaseId);
        }
        return entries.size();
    }

    // ------------------------------------------------------------ maths

    /**
     * An entry whose hold has already elapsed - because the hold period is zero,
     * or configured shorter than the clock skew - is payable straight away rather
     * than waiting for the release job to notice.
     */
    private EarningStatus statusFor(Instant availableAt) {
        return availableAt.isAfter(Instant.now()) ? EarningStatus.PENDING : EarningStatus.AVAILABLE;
    }

    private Split split(long grossMinor) {
        BigDecimal commission = BigDecimal.valueOf(grossMinor)
                .multiply(properties.commissionPercent())
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        long commissionMinor = commission.longValue();
        return new Split(grossMinor, commissionMinor, grossMinor - commissionMinor);
    }

    /** The gross a given net came from, so the ledger's split constraint holds. */
    private long grossFor(long netMinor) {
        BigDecimal creatorShare = BigDecimal.valueOf(100).subtract(properties.commissionPercent());
        if (creatorShare.signum() <= 0) {
            return netMinor;
        }
        return BigDecimal.valueOf(netMinor)
                .multiply(BigDecimal.valueOf(100))
                .divide(creatorShare, 0, RoundingMode.HALF_UP)
                .longValue();
    }

    public static String currentPeriod() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }

    private YearMonth parsePeriod(String period) {
        try {
            return YearMonth.parse(period);
        } catch (Exception e) {
            throw ApiException.badRequest("invalid_period", "Period must be yyyy-MM, e.g. 2026-07");
        }
    }

    private record Split(long gross, long commission, long net) {
    }
}
