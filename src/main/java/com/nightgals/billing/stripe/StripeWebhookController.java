package com.nightgals.billing.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.ConditionalOnPaymentProvider;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Where Stripe reports the outcome of a hosted checkout.
 *
 * <p>Unauthenticated, because Stripe has no credential of ours to present - but
 * unlike the Mobile Money callback it is not therefore untrusted. Stripe signs
 * every delivery with a secret shared only with this endpoint, so a body that
 * verifies <b>is</b> from Stripe and can be acted on. A body that does not
 * verify is rejected with a 400 and nothing happens.
 *
 * <p>That signature is the entire security boundary. Without the signing secret
 * this route is "anyone who can POST grants themselves paid content", so it
 * refuses to do anything at all while the secret is unset rather than degrading
 * to trusting the payload.
 *
 * <p>The raw body is taken as a String on purpose: signatures are computed over
 * the exact bytes Stripe sent, and letting Jackson parse and re-serialise first
 * would change them and fail every verification.
 */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/webhooks/stripe")
@ConditionalOnPaymentProvider("stripe")
public class StripeWebhookController {

    /**
     * Its own, rather than the application's.
     *
     * <p>This project pulls in webmvc without the Jackson auto-configuration that
     * publishes an {@code ObjectMapper} bean, so there is none to inject. That
     * suits the job anyway: two string fields are being read out of a payload
     * whose shape is Stripe's, and it should not shift because somebody changes
     * how this application serialises its own responses.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StripeGateway gateway;
    private final BillingService billing;

    @PostMapping
    public ResponseEntity<String> onEvent(@RequestBody String payload,
                                          @RequestHeader(value = "Stripe-Signature",
                                                  required = false) String signature) {
        if (!gateway.webhookSecretConfigured()) {
            log.error("Stripe webhook received but STRIPE_WEBHOOK_SECRET is unset - ignoring. "
                      + "Purchases will settle by reconciliation instead, which is slower.");
            return ResponseEntity.status(503).body("webhook secret not configured");
        }
        if (signature == null || signature.isBlank()) {
            log.warn("Stripe webhook with no Stripe-Signature header - rejected");
            return ResponseEntity.badRequest().body("missing signature");
        }

        Event event;
        try {
            event = gateway.parseWebhook(payload, signature);
        } catch (SignatureVerificationException e) {
            // Either someone is guessing, or the endpoint's secret does not match
            // the one in configuration - the latter being far more likely, and
            // silent, so it is logged as an error rather than a warning.
            log.error("Stripe webhook signature rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body("bad signature");
        }

        try {
            handle(event, payload);
        } catch (RuntimeException e) {
            // A 5xx makes Stripe retry, which is right for a transient fault and
            // harmless otherwise: settle() is idempotent.
            log.error("Stripe webhook {} ({}) failed", event.getId(), event.getType(), e);
            return ResponseEntity.status(500).body("retry");
        }

        return ResponseEntity.ok("ok");
    }

    /**
     * Acts on the events that change a purchase, and ignores the rest.
     *
     * <p>Stripe sends a great many event types and an endpoint receives whatever
     * it was subscribed to; anything unrecognised is acknowledged rather than
     * retried, since a 5xx would have Stripe redelivering an event forever that
     * this application will never care about.
     */
    private void handle(Event event, String payload) {
        String type = event.getType();
        UUID purchaseId = purchaseIdOf(payload);
        if (purchaseId == null) {
            log.warn("Stripe event {} ({}) carried no purchase id", event.getId(), type);
            return;
        }

        switch (type) {
            // The ordinary success. Card payments are synchronous, so by the time
            // this arrives payment_status is already paid.
            case "checkout.session.completed",
                 // Delayed methods settle later, after the session itself completed.
                 "checkout.session.async_payment_succeeded" ->
                    settleIfPaid(purchaseId, text(dataObject(payload), "id"));

            case "checkout.session.async_payment_failed" ->
                    failIfPending(purchaseId, "The payment was declined");

            // The payer never finished, and the page is no longer payable.
            case "checkout.session.expired" ->
                    failIfPending(purchaseId, "The payment page expired before it was completed");

            default -> log.debug("Stripe event {} ignored", type);
        }
    }

    /**
     * Fails a purchase, tolerating one that has already moved on.
     *
     * <p>{@link BillingService#fail} rejects anything not PENDING, which is right
     * for an administrator and wrong here: Stripe redelivers, and a second
     * {@code expired} for a purchase already failed would throw, return 500, and
     * have Stripe retry an event that can never succeed. Worth catching narrowly
     * rather than swallowing every failure.
     */
    private void failIfPending(UUID purchaseId, String reason) {
        try {
            billing.fail(purchaseId, reason);
            log.info("Stripe purchase {} failed: {}", purchaseId, reason);
        } catch (com.nightgals.common.ApiException e) {
            if ("not_pending".equals(e.getCode()) || "not_found".equals(e.getCode())) {
                log.debug("Stripe purchase {} was already resolved; nothing to fail", purchaseId);
                return;
            }
            throw e;
        }
    }

    /**
     * Settles only once Stripe says the money is there.
     *
     * <p>{@code checkout.session.completed} means the payer finished the page, not
     * that it was paid - a session using a delayed payment method completes while
     * still awaiting funds. Granting on the event alone would open paid content
     * against a payment that can still fail.
     */
    private void settleIfPaid(UUID purchaseId, String sessionId) {
        var session = sessionId == null
                ? java.util.Optional.<com.stripe.model.checkout.Session>empty()
                : gateway.session(sessionId);

        String paymentStatus = session.map(com.stripe.model.checkout.Session::getPaymentStatus)
                .orElse(null);
        if (!"paid".equals(paymentStatus) && !"no_payment_required".equals(paymentStatus)) {
            log.info("Stripe session {} for purchase {} is {} - not settling yet",
                    sessionId, purchaseId, paymentStatus);
            return;
        }

        String reference = session.map(com.stripe.model.checkout.Session::getPaymentIntent)
                .filter(id -> !id.isBlank())
                .orElse(sessionId);

        billing.settle(purchaseId, reference);
        log.info("Stripe purchase {} settled by webhook ({})", purchaseId, reference);
    }

    /**
     * The purchase id, read from the raw body rather than a deserialised object.
     *
     * <p>Stripe stamps each event with the API version of the account that
     * created it, and the SDK's typed deserialiser refuses objects from a version
     * it was not built for - which turns a dashboard-side version bump into
     * silently unprocessed payments. The two fields wanted here have been stable
     * for years, so reading them out of the JSON is both simpler and sturdier.
     */
    private UUID purchaseIdOf(String payload) {
        JsonNode object = dataObject(payload);
        if (object == null) {
            return null;
        }
        String raw = text(object, "client_reference_id");
        if (raw == null) {
            JsonNode metadata = object.get("metadata");
            raw = metadata == null ? null : text(metadata, "purchaseId");
        }
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Stripe event reference {} is not a purchase id", raw);
            return null;
        }
    }

    private JsonNode dataObject(String payload) {
        try {
            JsonNode data = JSON.readTree(payload).path("data").path("object");
            return data.isMissingNode() || data.isNull() ? null : data;
        } catch (Exception e) {
            log.warn("Stripe event body could not be read: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
