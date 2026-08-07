package com.nightgals.billing.momo;

import com.nightgals.billing.ConditionalOnPaymentProvider;
import com.nightgals.config.MomoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The wire protocol, and nothing else. No purchases, no entitlements.
 *
 * <p>Three calls make up the whole Collections flow:
 *
 * <ol>
 *   <li>{@code POST /collection/token/} - Basic auth with the API user and key,
 *       returns a bearer token good for an hour.
 *   <li>{@code POST /collection/v1_0/requesttopay} - pushes a prompt to the
 *       payer's handset. Returns <b>202 with an empty body</b>: the reference is
 *       the {@code X-Reference-Id} we generated, not something MTN hands back.
 *   <li>{@code GET /collection/v1_0/requesttopay/{ref}} - PENDING, SUCCESSFUL or
 *       FAILED. This is the only source of truth; the callback is a hint that
 *       arrives sooner.
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnPaymentProvider("momo")
public class MomoClient {

    private final MomoProperties properties;
    private final RestClient http;

    // Guards the token swap. Requests are served on virtual threads, so without
    // this every concurrent checkout during an expiry would mint its own token.
    private final ReentrantLock tokenLock = new ReentrantLock();
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public MomoClient(MomoProperties properties) {
        this.properties = properties;
        // Built here rather than injected: this project pulls in webmvc without
        // the auto-configured RestClient.Builder, and a client talking to one
        // fixed host has nothing to share with the rest of the application.
        this.http = RestClient.builder().baseUrl(properties.baseUrl()).build();
    }

    /**
     * Asks MTN to prompt the payer.
     *
     * @param reference our idempotency key, and the id every later lookup uses
     * @return true if MTN accepted the request for processing
     */
    public boolean requestToPay(UUID reference, String msisdn, long amount,
                                String externalId, String payerMessage) {
        var body = Map.of(
                // A string, always. XAF has no minor unit, so this is the price
                // as written - nothing here divides by 100.
                "amount", String.valueOf(amount),
                "currency", properties.currency(),
                "externalId", externalId,
                "payer", Map.of("partyIdType", "MSISDN", "partyId", msisdn),
                "payerMessage", payerMessage,
                "payeeNote", payerMessage);

        try {
            http.post()
                    .uri("/collection/v1_0/requesttopay")
                    .headers(h -> {
                        authorise(h);
                        h.set("X-Reference-Id", reference.toString());
                        if (properties.callbackUrl() != null && !properties.callbackUrl().isBlank()) {
                            h.set("X-Callback-Url", properties.callbackUrl());
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            // 409 means we have already used this reference: the prompt is
            // already out, so re-sending would double-charge if MTN honoured it.
            if (e.getStatusCode().value() == 409) {
                log.warn("MoMo reference {} already submitted - treating as in flight", reference);
                return true;
            }
            log.error("MoMo requesttopay {} rejected: {} {}",
                    reference, e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        }
    }

    /** Where a payment actually stands. Empty when MTN cannot be reached. */
    public java.util.Optional<Status> status(UUID reference) {
        try {
            Map<?, ?> body = http.get()
                    .uri("/collection/v1_0/requesttopay/{ref}", reference)
                    .headers(this::authorise)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Status(
                    String.valueOf(body.get("status")),
                    body.get("reason") == null ? null : String.valueOf(body.get("reason")),
                    body.get("financialTransactionId") == null
                            ? null : String.valueOf(body.get("financialTransactionId"))));
        } catch (RestClientResponseException e) {
            log.warn("MoMo status {} unavailable: {} {}",
                    reference, e.getStatusCode(), e.getResponseBodyAsString());
            return java.util.Optional.empty();
        }
    }

    private void authorise(HttpHeaders headers) {
        headers.setBearerAuth(accessToken());
        headers.set("X-Target-Environment", properties.targetEnvironment());
        headers.set("Ocp-Apim-Subscription-Key", properties.subscriptionKey());
    }

    private String accessToken() {
        Duration margin = properties.tokenRefreshMargin() == null
                ? Duration.ofMinutes(5) : properties.tokenRefreshMargin();
        if (token != null && Instant.now().isBefore(tokenExpiresAt.minus(margin))) {
            return token;
        }
        tokenLock.lock();
        try {
            if (token != null && Instant.now().isBefore(tokenExpiresAt.minus(margin))) {
                return token;
            }
            String basic = Base64.getEncoder().encodeToString(
                    (properties.apiUser() + ":" + properties.apiKey()).getBytes(StandardCharsets.UTF_8));

            Map<?, ?> body = http.post()
                    .uri("/collection/token/")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .header("Ocp-Apim-Subscription-Key", properties.subscriptionKey())
                    .retrieve()
                    .body(Map.class);

            if (body == null || body.get("access_token") == null) {
                throw new IllegalStateException("MoMo token response had no access_token");
            }
            token = String.valueOf(body.get("access_token"));
            long ttl = body.get("expires_in") == null
                    ? 3600 : Long.parseLong(String.valueOf(body.get("expires_in")));
            tokenExpiresAt = Instant.now().plusSeconds(ttl);
            return token;
        } finally {
            tokenLock.unlock();
        }
    }

    /** @param status PENDING, SUCCESSFUL or FAILED */
    public record Status(String status, String reason, String financialTransactionId) {

        public boolean successful() {
            return "SUCCESSFUL".equalsIgnoreCase(status);
        }

        public boolean failed() {
            return "FAILED".equalsIgnoreCase(status);
        }
    }
}
