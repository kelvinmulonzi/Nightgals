package com.nightgals.billing.orange;

import com.nightgals.billing.ConditionalOnPaymentProvider;
import com.nightgals.billing.Purchase;
import com.nightgals.config.OrangeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The wire protocol, and nothing else. No purchases, no entitlements.
 *
 * <p>Four calls make up the whole Merchant Payment (MP) flow, all under
 * {@code /omcoreapis/1.0.2/mp/}:
 *
 * <ol>
 *   <li>{@code POST /token} - Basic auth with the OAuth client id and secret,
 *       returns a bearer token good for about an hour. This is the only call
 *       that does not also carry {@code X-AUTH-TOKEN}.
 *   <li>{@code POST mp/init} - empty body, mints a {@code payToken}. Nothing is
 *       pushed to anyone yet.
 *   <li>{@code POST mp/pay} - spends that {@code payToken} against a payer's
 *       number and this merchant's own channel credentials. Returns {@code
 *       PENDING}: the payer still has to approve it on their handset.
 *   <li>{@code GET mp/paymentstatus/{payToken}} - the current status. This is
 *       the only source of truth; the notification is a hint that arrives
 *       sooner.
 * </ol>
 *
 * <p>Every call from {@code mp/init} onward needs <b>both</b> the bearer token
 * from step 1 <i>and</i> the static {@code X-AUTH-TOKEN} issued once by Orange
 * during onboarding - carrying only one fails the call the same way as
 * carrying neither.
 */
@Slf4j
@Component
@ConditionalOnPaymentProvider("orange")
public class OrangeClient {

    private static final String MP_PATH = "/omcoreapis/1.0.2/mp";

    private final OrangeProperties properties;
    private final RestClient http;

    // Guards the token swap. Requests are served on virtual threads, so without
    // this every concurrent checkout during an expiry would mint its own token.
    private final ReentrantLock tokenLock = new ReentrantLock();
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public OrangeClient(OrangeProperties properties) {
        this.properties = properties;
        // Built here rather than injected, same reasoning as MomoClient: this
        // project has no auto-configured RestClient.Builder, and a client talking
        // to one fixed host has nothing to share with the rest of the application.
        Duration timeout = properties.timeout() == null ? Duration.ofSeconds(10) : properties.timeout();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(new BufferingClientHttpRequestFactory(factory))
                .requestInterceptor(this::logExchange)
                .build();
    }

    /**
     * Every call to Orange and its answer, at DEBUG. Credentials never reach the
     * log: both auth headers are masked, and so is the access token in a token
     * response.
     */
    private ClientHttpResponse logExchange(HttpRequest request, byte[] body,
                                           ClientHttpRequestExecution execution) throws java.io.IOException {
        if (!log.isDebugEnabled()) {
            return execution.execute(request, body);
        }
        HttpHeaders shown = new HttpHeaders();
        request.getHeaders().forEach((name, values) -> shown.put(name,
                HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                        ? values.stream().map(v -> v.substring(0, Math.max(0, v.indexOf(' '))) + " ****").toList()
                        : "X-AUTH-TOKEN".equalsIgnoreCase(name) ? java.util.List.of("****") : values));
        log.debug("Orange -> {} {} headers={} body={}", request.getMethod(), request.getURI(), shown,
                body.length == 0 ? "<empty>" : new String(body, StandardCharsets.UTF_8)
                        .replaceAll("(\"pin\"\\s*:\\s*\")[^\"]+", "$1****"));

        long started = System.nanoTime();
        ClientHttpResponse response = execution.execute(request, body);
        String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)
                .replaceAll("(\"access_token\"\\s*:\\s*\")[^\"]+", "$1****");
        log.debug("Orange <- {} {} {}ms body={}", response.getStatusCode().value(), request.getURI().getPath(),
                (System.nanoTime() - started) / 1_000_000,
                responseBody.isEmpty() ? "<empty>" : responseBody);
        return response;
    }

    /**
     * Mints a {@code payToken} and immediately spends it against the payer.
     *
     * @return the {@code payToken}, which is Orange's id for this payment and
     *         what every later status check uses - empty when either call was
     *         refused or Orange could not be reached
     */
    public Optional<String> requestToPay(Purchase purchase, String subscriberMsisdn) {
        String payToken = init(purchase).orElse(null);
        if (payToken == null) {
            return Optional.empty();
        }
        return pay(purchase, subscriberMsisdn, payToken) ? Optional.of(payToken) : Optional.empty();
    }

    private Optional<String> init(Purchase purchase) {
        try {
            Map<?, ?> response = http.post()
                    .uri(MP_PATH + "/init")
                    .headers(this::authorise)
                    .retrieve()
                    .body(Map.class);
            Object data = response == null ? null : response.get("data");
            Object payToken = data instanceof Map<?, ?> map ? map.get("payToken") : null;
            if (payToken == null) {
                log.error("Orange mp/init for purchase {} came back with no payToken: {}",
                        purchase.getId(), response);
                return Optional.empty();
            }
            return Optional.of(String.valueOf(payToken));
        } catch (RestClientResponseException e) {
            log.error("Orange mp/init for purchase {} rejected: {} {}",
                    purchase.getId(), e.getStatusCode(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (RestClientException e) {
            log.error("Orange mp/init for purchase {} failed: {}", purchase.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private boolean pay(Purchase purchase, String subscriberMsisdn, String payToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subscriberMsisdn", subscriberMsisdn);
        body.put("channelUserMsisdn", properties.channelUserMsisdn());
        body.put("amount", purchase.getAmountMinor());
        body.put("description", "Nightgals " + purchase.getType().name().toLowerCase().replace('_', ' '));
        body.put("orderId", purchase.getId().toString());
        body.put("pin", properties.channelPin());
        body.put("payToken", payToken);
        if (properties.notifUrl() != null && !properties.notifUrl().isBlank()) {
            body.put("notifUrl", properties.notifUrl());
        }

        try {
            http.post()
                    .uri(MP_PATH + "/pay")
                    .headers(this::authorise)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            log.error("Orange mp/pay for purchase {} rejected: {} {}",
                    purchase.getId(), e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (RestClientException e) {
            // Timed out or never connected. Orange may or may not have the
            // request; a retried checkout mints a fresh payToken via init, so
            // this cannot push twice against the same one.
            log.error("Orange mp/pay for purchase {} failed: {}", purchase.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Where a payment actually stands.
     *
     * <p>Reads {@link Purchase#getProviderReference()} for the {@code payToken}
     * that {@code BillingService} stored when the payment started, rather than
     * taking a bare id - Orange's own reference is what this endpoint is keyed
     * on, not the purchase's.
     *
     * @return empty when the purchase has no pay token yet, or Orange cannot be reached
     */
    public Optional<Status> status(Purchase purchase) {
        String payToken = purchase.getProviderReference();
        if (payToken == null || payToken.isBlank()) {
            log.warn("Orange status check for purchase {} has no pay token to check", purchase.getId());
            return Optional.empty();
        }
        try {
            Map<?, ?> response = http.get()
                    .uri(MP_PATH + "/paymentstatus/{payToken}", payToken)
                    .headers(this::authorise)
                    .retrieve()
                    .body(Map.class);
            Object data = response == null ? null : response.get("data");
            if (!(data instanceof Map<?, ?> map) || map.get("status") == null) {
                return Optional.empty();
            }
            return Optional.of(new Status(String.valueOf(map.get("status")),
                    map.get("txnid") == null ? null : String.valueOf(map.get("txnid"))));
        } catch (RestClientResponseException e) {
            log.warn("Orange status for purchase {} unavailable: {} {}",
                    purchase.getId(), e.getStatusCode(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (RestClientException e) {
            // Timed out or never connected: no answer this time, ask again next sweep.
            log.warn("Orange status for purchase {} unavailable: {}", purchase.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private void authorise(HttpHeaders headers) {
        headers.setBearerAuth(accessToken());
        headers.set("X-AUTH-TOKEN", properties.staticAuthToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
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
                    (properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));

            Map<?, ?> body = http.post()
                    .uri("/token")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials")
                    .retrieve()
                    .body(Map.class);

            if (body == null || body.get("access_token") == null) {
                throw new IllegalStateException("Orange token response had no access_token");
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

    /**
     * @param status PENDING while the payer has not answered, then one of
     *               Orange's success or failure spellings - {@code SUCCESSFUL}
     *               and {@code SUCCESSFULL} have both been observed across
     *               endpoints, so both are treated as success.
     */
    public record Status(String status, String transactionId) {

        public boolean successful() {
            return status != null && status.toUpperCase(java.util.Locale.ROOT).startsWith("SUCCESS");
        }

        public boolean failed() {
            return "FAILED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status)
                   || "CANCELLED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status);
        }
    }
}
