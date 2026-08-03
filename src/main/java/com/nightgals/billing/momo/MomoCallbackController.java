package com.nightgals.billing.momo;

import com.nightgals.billing.BillingService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Where MTN reports the outcome of a prompt.
 *
 * <p>Unauthenticated by necessity - MTN has no credential of ours to present -
 * so <b>the body is treated as a rumour, never as truth</b>. It says only
 * "purchase X may have changed"; the actual state is then read back from MTN
 * over an authenticated call. Settling straight from this payload would let
 * anyone who guesses a purchase id unlock content for free.
 *
 * <p>Registered as {@code X-Callback-Url} per request. MTN sends PUT; POST is
 * accepted too because the documentation has said both at different times.
 */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/webhooks/momo")
@ConditionalOnProperty(name = "nightgals.monetization.provider", havingValue = "momo")
public class MomoCallbackController {

    private final MomoClient client;
    private final BillingService billing;

    // One @RequestMapping listing both verbs, not stacked @PutMapping/@PostMapping -
    // Spring keeps only the first of those and drops the other silently.
    @RequestMapping(method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<Void> onCallback(@RequestBody(required = false) Map<String, Object> body) {
        // externalId is the purchase id we sent; referenceId appears on some
        // payloads. Either identifies the purchase - and neither is trusted for
        // anything beyond that.
        String reference = firstNonBlank(
                body == null ? null : body.get("externalId"),
                body == null ? null : body.get("referenceId"));

        if (reference == null) {
            log.warn("MoMo callback carried no reference: {}", body);
            return ResponseEntity.ok().build();
        }

        UUID purchaseId;
        try {
            purchaseId = UUID.fromString(reference);
        } catch (IllegalArgumentException e) {
            log.warn("MoMo callback reference {} is not a purchase id", reference);
            return ResponseEntity.ok().build();
        }

        // The authenticated read-back. This is the part that decides anything.
        client.status(purchaseId).ifPresent(status -> {
            if (status.successful()) {
                billing.settle(purchaseId, status.financialTransactionId() == null
                        ? purchaseId.toString() : status.financialTransactionId());
                log.info("MoMo purchase {} settled by callback", purchaseId);
            } else if (status.failed()) {
                billing.fail(purchaseId, status.reason() == null ? "Payment failed" : status.reason());
                log.info("MoMo purchase {} failed by callback: {}", purchaseId, status.reason());
            }
        });

        // Always 200. A non-2xx makes MTN retry, and a retry cannot fix a
        // malformed payload - MomoReconciler is the safety net that can.
        return ResponseEntity.ok().build();
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
