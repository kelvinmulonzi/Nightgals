package com.nightgals.billing.momo;

import com.nightgals.billing.BillingService;
import com.nightgals.billing.ConditionalOnPaymentProvider;
import com.nightgals.billing.Purchase;
import com.nightgals.billing.PurchaseRepository;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.config.MomoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Asks MTN what happened to purchases still sitting PENDING.
 *
 * <p>The callback is an optimisation, not a guarantee: it needs a public HTTPS
 * host, it is not retried forever, and in local development there is usually
 * nowhere for it to land at all. Without this sweep a lost callback means a
 * viewer paid and nothing unlocked - the single worst failure this system can
 * have, because the money left their account.
 *
 * <p>Purchases older than {@link MomoProperties#reconcileWindow()} stop being
 * chased and are marked failed. A prompt nobody answered expires on MTN's side
 * anyway; leaving it PENDING forever just grows the sweep.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnPaymentProvider("momo")
public class MomoReconciler {

    private final PurchaseRepository purchases;
    private final BillingService billing;
    private final MomoClient client;
    private final MomoProperties properties;

    @Scheduled(cron = "${nightgals.momo.reconcile-cron:0 */2 * * * *}")
    public void reconcile() {
        Duration window = properties.reconcileWindow() == null
                ? Duration.ofHours(1) : properties.reconcileWindow();
        Instant cutoff = Instant.now().minus(window);

        for (Purchase purchase : purchases.findByStatusAndProvider(PurchaseStatus.PENDING, "MOMO")) {
            if (purchase.getCreatedAt() != null && purchase.getCreatedAt().isBefore(cutoff)) {
                log.info("Abandoning MoMo purchase {} - unanswered for {}", purchase.getId(), window);
                billing.fail(purchase.getId(), "Payment was not approved in time");
                continue;
            }

            client.status(purchase.getId()).ifPresent(status -> {
                if (status.successful()) {
                    log.info("MoMo purchase {} settled by reconciliation", purchase.getId());
                    billing.settle(purchase.getId(),
                            status.financialTransactionId() == null
                                    ? purchase.getId().toString() : status.financialTransactionId());
                } else if (status.failed()) {
                    log.info("MoMo purchase {} failed: {}", purchase.getId(), status.reason());
                    billing.fail(purchase.getId(),
                            status.reason() == null ? "Payment failed" : status.reason());
                }
                // PENDING: the payer has not decided yet. Leave it alone.
            });
        }
    }
}
