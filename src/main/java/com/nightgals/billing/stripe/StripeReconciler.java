package com.nightgals.billing.stripe;

import com.nightgals.billing.BillingService;
import com.nightgals.billing.ConditionalOnPaymentProvider;
import com.nightgals.billing.Purchase;
import com.nightgals.billing.PurchaseRepository;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.common.ApiException;
import com.nightgals.config.StripeProperties;
import com.stripe.model.checkout.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Asks Stripe what happened to purchases still sitting PENDING.
 *
 * <p>Stripe retries a failing webhook for days, which covers an endpoint that is
 * down - but not one that was never reachable, never registered, or registered
 * against the wrong signing secret. Every one of those presents identically:
 * money leaves the payer's card and nothing unlocks. This sweep is what makes
 * the integration honest when no webhook ever arrives, and is the reason the
 * platform is safe to run before the webhook is configured at all.
 *
 * <p>Purchases older than {@link StripeProperties#reconcileWindow()} stop being
 * chased and are marked failed. Their session has long expired on Stripe's side,
 * so leaving them PENDING only grows the sweep.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnPaymentProvider("stripe")
public class StripeReconciler {

    private final PurchaseRepository purchases;
    private final BillingService billing;
    private final StripeGateway gateway;
    private final StripeProperties properties;

    @Scheduled(cron = "${nightgals.stripe.reconcile-cron:0 */2 * * * *}")
    public void reconcile() {
        Duration window = properties.reconcileWindow() == null
                ? Duration.ofDays(1) : properties.reconcileWindow();
        Instant cutoff = Instant.now().minus(window);

        for (Purchase purchase : purchases.findByStatusAndProvider(PurchaseStatus.PENDING, "STRIPE")) {
            String sessionId = purchase.getProviderReference();
            if (sessionId == null || sessionId.isBlank()) {
                // startPayment threw before a session existed, so there is nothing
                // at Stripe to ask about and no money at risk.
                if (isStale(purchase, cutoff)) {
                    fail(purchase, "Payment was never started");
                }
                continue;
            }

            var session = gateway.session(sessionId);
            if (session.isEmpty()) {
                // Stripe unreachable. Leave it PENDING and try again next sweep -
                // failing a purchase because our network blipped would be worse.
                continue;
            }
            apply(purchase, session.get(), cutoff);
        }
    }

    private void apply(Purchase purchase, Session session, Instant cutoff) {
        String paymentStatus = session.getPaymentStatus();

        if ("paid".equals(paymentStatus) || "no_payment_required".equals(paymentStatus)) {
            String reference = session.getPaymentIntent() == null || session.getPaymentIntent().isBlank()
                    ? session.getId() : session.getPaymentIntent();
            log.info("Stripe purchase {} settled by reconciliation", purchase.getId());
            settle(purchase, reference);
            return;
        }

        if ("expired".equals(session.getStatus())) {
            fail(purchase, "The payment page expired before it was completed");
            return;
        }

        // Still open and unpaid: the payer has not finished. Leave it alone until
        // the session expires or the window runs out.
        if (isStale(purchase, cutoff)) {
            log.info("Abandoning Stripe purchase {} - opened {} and never paid",
                    purchase.getId(), purchase.getCreatedAt());
            fail(purchase, "Payment was not completed in time");
        }
    }

    private static boolean isStale(Purchase purchase, Instant cutoff) {
        return purchase.getCreatedAt() != null && purchase.getCreatedAt().isBefore(cutoff);
    }

    /*
     * Both wrapped: the sweep holds rows read at the top of the loop, and a
     * webhook may settle one of them mid-pass. Losing that race is normal and
     * must not abort the rest of the sweep.
     */

    private void settle(Purchase purchase, String reference) {
        try {
            billing.settle(purchase.getId(), reference);
        } catch (ApiException e) {
            log.debug("Stripe purchase {} already resolved: {}", purchase.getId(), e.getCode());
        }
    }

    private void fail(Purchase purchase, String reason) {
        try {
            billing.fail(purchase.getId(), reason);
            log.info("Stripe purchase {} failed: {}", purchase.getId(), reason);
        } catch (ApiException e) {
            log.debug("Stripe purchase {} already resolved: {}", purchase.getId(), e.getCode());
        }
    }
}
