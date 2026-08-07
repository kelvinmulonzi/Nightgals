package com.nightgals.billing.stripe;

import com.nightgals.billing.ConditionalOnPaymentProvider;
import com.nightgals.billing.Purchase;
import com.nightgals.config.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The wire protocol, and nothing else. No purchases, no entitlements.
 *
 * <p>Named for what it is rather than {@code StripeClient}, which is the SDK's
 * own type and is held inside this one.
 *
 * <p>Two calls make up the whole flow:
 *
 * <ol>
 *   <li>{@code POST /v1/checkout/sessions} - creates the hosted page and returns
 *       its {@code url} and {@code cs_...} id. No money has moved.
 *   <li>{@code GET /v1/checkout/sessions/{id}} - {@code payment_status} becomes
 *       {@code paid} once it has. This is the only source of truth; the webhook
 *       is a hint that arrives sooner.
 * </ol>
 *
 * <p>Amounts pass through untouched. {@code amountMinor} is already in the
 * currency's smallest unit, which is exactly what Stripe's {@code unit_amount}
 * means - 15000 XAF is fifteen thousand francs to both, and nothing here divides
 * by 100. That holds because {@link com.nightgals.common.Money} and Stripe agree
 * on which currencies have no minor unit; they differ on ISK and MGA, neither of
 * which this platform prices in. Check before adding a market that does.
 */
@Slf4j
@Component
@ConditionalOnPaymentProvider("stripe")
public class StripeGateway {

    /** Stripe rejects an expiry outside this range. */
    private static final Duration MIN_TTL = Duration.ofMinutes(30);
    private static final Duration MAX_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private final StripeProperties properties;
    private final com.stripe.StripeClient client;

    public StripeGateway(StripeProperties properties) {
        this.properties = properties;
        if (properties.secretKey() == null || properties.secretKey().isBlank()) {
            // Failing here, at startup, rather than on the first checkout: a
            // deployment that lists stripe as a payment method without giving it a
            // key is misconfigured, and finding that out from a buyer is worse.
            throw new IllegalStateException(
                    "STRIPE_SECRET_KEY is not set, but 'stripe' is listed in "
                    + "nightgals.monetization.providers. Set the key or drop the provider.");
        }
        if (properties.secretKey().startsWith("pk_")) {
            // Easy mistake, and the error Stripe gives for it is not obvious.
            throw new IllegalStateException(
                    "STRIPE_SECRET_KEY holds a publishable key (pk_...). The secret key "
                    + "starts with sk_ and is revealed separately in the dashboard.");
        }
        this.client = new com.stripe.StripeClient(properties.secretKey());
        log.info("Stripe ready in {} mode", properties.secretKey().contains("_live_") ? "LIVE" : "test");
    }

    /**
     * Opens a hosted payment page for a purchase.
     *
     * <p>The purchase id travels three ways - {@code client_reference_id}, session
     * metadata and payment-intent metadata - because each is what some part of
     * Stripe shows you. The first is what the webhook reads; the last is what
     * appears on the payment itself when somebody is chasing a dispute months
     * later and has only the charge in front of them.
     */
    public Session createSession(Purchase purchase, String description, String buyerEmail)
            throws StripeException {

        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(url(properties.successUrl(), purchase))
                .setCancelUrl(url(properties.cancelUrl(), purchase))
                .setClientReferenceId(purchase.getId().toString())
                .setExpiresAt(Instant.now().plus(sessionTtl()).getEpochSecond())
                .putMetadata("purchaseId", purchase.getId().toString())
                .putMetadata("purchaseType", purchase.getType().name())
                .putMetadata("userId", purchase.getUser().getId().toString())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .setDescription(description)
                        .putMetadata("purchaseId", purchase.getId().toString())
                        .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                // What is owed after credit, not the sticker price:
                                // referral credit is already deducted by the time a
                                // provider is asked for anything.
                                .setUnitAmount(purchase.getAmountMinor())
                                .setCurrency(purchase.getCurrency().toLowerCase(java.util.Locale.ROOT))
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData
                                        .builder()
                                        .setName(description)
                                        .build())
                                .build())
                        .build());

        // Lets Stripe send its own receipt, and saves the payer typing it. Absent
        // only for accounts that somehow have no address on file.
        if (buyerEmail != null && !buyerEmail.isBlank()) {
            params.setCustomerEmail(buyerEmail);
        }

        return client.checkout().sessions().create(params.build());
    }

    /** Where a payment actually stands. Empty when Stripe cannot be reached. */
    public Optional<Session> session(String sessionId) {
        try {
            return Optional.ofNullable(client.checkout().sessions().retrieve(sessionId));
        } catch (StripeException e) {
            log.warn("Stripe session {} unavailable: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Verifies a webhook's signature and parses it.
     *
     * @throws SignatureVerificationException when the body was not signed by
     *         Stripe with our endpoint's secret - meaning it is not from Stripe
     */
    public Event parseWebhook(String payload, String signatureHeader)
            throws SignatureVerificationException {
        return Webhook.constructEvent(payload, signatureHeader, properties.webhookSecret());
    }

    public boolean webhookSecretConfigured() {
        return properties.webhookSecret() != null && !properties.webhookSecret().isBlank();
    }

    public String publishableKey() {
        return properties.publishableKey();
    }

    /** Clamped, because Stripe rejects the request outright rather than clamping. */
    private Duration sessionTtl() {
        Duration ttl = properties.sessionTtl() == null ? DEFAULT_TTL : properties.sessionTtl();
        if (ttl.compareTo(MIN_TTL) < 0) {
            return MIN_TTL;
        }
        return ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
    }

    private static String url(String template, Purchase purchase) {
        if (template == null || template.isBlank()) {
            throw new IllegalStateException(
                    "nightgals.stripe.success-url and cancel-url must both be set - "
                    + "Stripe refuses to create a session without somewhere to return to.");
        }
        return template.replace("{purchaseId}", purchase.getId().toString());
    }
}
