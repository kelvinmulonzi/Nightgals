package com.nightgals.billing.orange;

import com.nightgals.billing.BillingService;
import com.nightgals.billing.ConditionalOnPaymentProvider;
import com.nightgals.billing.Purchase;
import com.nightgals.billing.PurchaseRepository;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where Orange reports the outcome of an {@code mp/pay} push.
 *
 * <p>Unauthenticated by necessity - Orange has no credential of ours to present,
 * and the OMAPI guide does not document this payload's exact shape at all - so
 * <b>the body is treated as a rumour, never as truth</b>. It says only "purchase
 * X may have changed"; the actual state is then read back from Orange over an
 * authenticated {@link OrangeClient#status} call. Settling straight from this
 * payload would let anyone who guesses or replays a body unlock content for
 * free.
 *
 * <p>Sent as {@code notifUrl} on each {@code mp/pay} call, camelCase like every
 * other field this API uses. Several candidate field names are tried rather
 * than one, since the shape is unconfirmed - the read-back is what actually
 * decides anything, so a wrong guess here costs a slightly slower settlement,
 * not a false one.
 */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/webhooks/orange")
@ConditionalOnPaymentProvider("orange")
public class OrangeCallbackController {

    private final OrangeClient client;
    private final PurchaseRepository purchases;
    private final BillingService billing;

    @RequestMapping(method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<Void> onNotification(@RequestBody(required = false) Map<String, Object> body) {
        Purchase purchase = resolvePurchase(body).orElse(null);
        if (purchase == null) {
            log.warn("Orange notification matched no purchase: {}", body);
            return ResponseEntity.ok().build();
        }
        if (purchase.getStatus() != com.nightgals.billing.PurchaseStatus.PENDING) {
            // Already settled by a previous notification or the reconciler.
            return ResponseEntity.ok().build();
        }

        // The authenticated read-back. This is the part that decides anything.
        client.status(purchase).ifPresent(status -> {
            if (status.successful()) {
                billing.settle(purchase.getId(), status.transactionId() == null
                        ? purchase.getId().toString() : status.transactionId());
                log.info("Orange purchase {} settled by notification", purchase.getId());
            } else if (status.failed()) {
                billing.fail(purchase.getId(), "Payment failed");
                log.info("Orange purchase {} failed by notification", purchase.getId());
            }
            // INITIATED/PENDING: the payer has not decided yet. Leave it alone.
        });

        // Always 200. A non-2xx makes Orange retry, and a retry cannot fix a
        // malformed payload - OrangeReconciler is the safety net that can.
        return ResponseEntity.ok().build();
    }

    /**
     * {@code orderId} is the purchase id we sent, so it is tried first. Failing
     * that, {@code payToken} is what {@code BillingService} stored as {@link
     * Purchase#getProviderReference()} when the payment started.
     */
    private Optional<Purchase> resolvePurchase(Map<String, Object> body) {
        if (body == null) {
            return Optional.empty();
        }
        String orderId = firstNonBlank(body.get("orderId"), body.get("order_id"));
        if (orderId != null) {
            try {
                Optional<Purchase> found = purchases.findById(UUID.fromString(orderId));
                if (found.isPresent()) {
                    return found;
                }
            } catch (IllegalArgumentException e) {
                log.warn("Orange notification orderId {} is not a purchase id", orderId);
            }
        }
        String payToken = firstNonBlank(body.get("payToken"), body.get("pay_token"));
        if (payToken != null) {
            return purchases.findByProviderAndProviderReference("ORANGE", payToken);
        }
        return Optional.empty();
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
