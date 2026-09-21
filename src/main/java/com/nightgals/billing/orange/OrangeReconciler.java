package com.nightgals.billing.orange;

import com.nightgals.billing.BillingService;
import com.nightgals.billing.ConditionalOnPaymentProvider;
import com.nightgals.billing.Purchase;
import com.nightgals.billing.PurchaseRepository;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.config.OrangeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Asks Orange what happened to purchases still sitting PENDING.
 *
 * <p>Same reasoning as {@link com.nightgals.billing.momo.MomoReconciler}: the
 * notification is an optimisation, not a guarantee. It needs a public HTTPS
 * host, is not retried forever, and usually has nowhere to land in local
 * development at all. Without this sweep a lost notification means a viewer
 * paid and nothing unlocked - the single worst failure this system can have,
 * because the money left their account.
 *
 * <p>Purchases older than {@link OrangeProperties#reconcileWindow()} stop being
 * chased and are marked failed. A page nobody finished expires on Orange's side
 * anyway (10 minutes, by default); leaving it PENDING forever just grows the
 * sweep.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnPaymentProvider("orange")
public class OrangeReconciler {

    private final PurchaseRepository purchases;
    private final BillingService billing;
    private final OrangeClient client;
    private final OrangeProperties properties;

    @Scheduled(cron = "${nightgals.orange.reconcile-cron:0 */2 * * * *}")
    public void reconcile() {
        Duration window = properties.reconcileWindow() == null
                ? Duration.ofHours(1) : properties.reconcileWindow();
        Instant cutoff = Instant.now().minus(window);

        for (Purchase purchase : purchases.findByStatusAndProvider(PurchaseStatus.PENDING, "ORANGE")) {
            if (purchase.getCreatedAt() != null && purchase.getCreatedAt().isBefore(cutoff)) {
                log.info("Abandoning Orange purchase {} - unanswered for {}", purchase.getId(), window);
                billing.fail(purchase.getId(), "Payment was not completed in time");
                continue;
            }

            client.status(purchase).ifPresent(status -> {
                if (status.successful()) {
                    log.info("Orange purchase {} settled by reconciliation", purchase.getId());
                    billing.settle(purchase.getId(),
                            status.transactionId() == null ? purchase.getId().toString() : status.transactionId());
                } else if (status.failed()) {
                    log.info("Orange purchase {} failed: {}", purchase.getId(), status.status());
                    billing.fail(purchase.getId(), "Payment failed");
                }
                // INITIATED/PENDING: the payer has not decided yet. Leave it alone.
            });
        }
    }
}
