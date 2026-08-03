package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MTN Mobile Money, which is how most of Cameroon actually pays for anything.
 *
 * <p>Two environments, and almost everything differs between them. Sandbox is
 * self-service: you subscribe to the Collections product for a subscription key,
 * then create your own API user and key over the API. Production credentials
 * come out of a commercial agreement with MTN Cameroon, and the target
 * environment stops being the literal string {@code sandbox}.
 *
 * <p>The trap worth writing down: <b>sandbox only accepts EUR</b>. Live XAF
 * amounts sent to it come back as a 400 that says nothing useful, so
 * {@link #currency} is configured separately from
 * {@code nightgals.monetization.currency} rather than derived from it.
 */
@ConfigurationProperties(prefix = "nightgals.momo")
public record MomoProperties(

        /** {@code https://sandbox.momodeveloper.mtn.com} until MTN says otherwise. */
        String baseUrl,

        /** Ocp-Apim-Subscription-Key for the Collections product. */
        String subscriptionKey,

        /**
         * The API user UUID and its key. In sandbox you mint both yourself:
         * {@code POST /v1_0/apiuser} with an X-Reference-Id, then
         * {@code POST /v1_0/apiuser/{id}/apikey}. In production MTN issues them.
         */
        String apiUser,
        String apiKey,

        /** {@code sandbox}, or the production environment name MTN assigns. */
        String targetEnvironment,

        /**
         * What to put in the request body. EUR in sandbox regardless of what the
         * product prices things in; XAF once live.
         */
        String currency,

        /**
         * Where MTN PUTs the result. Must be HTTPS and reachable from the
         * internet, so on a laptop this means a tunnel - or leave it blank and
         * rely on {@link #reconcileCron} alone, which is what sandbox work
         * usually does.
         */
        String callbackUrl,

        /** Access tokens last an hour; renew this early to avoid racing expiry. */
        Duration tokenRefreshMargin,

        /**
         * Sweep that asks MTN about purchases still PENDING.
         *
         * <p>Not an optimisation. Callbacks get lost, and a viewer who paid and
         * saw nothing unlock is a support ticket - polling is what makes the
         * integration honest when the callback never arrives.
         */
        String reconcileCron,

        /** How long a PENDING purchase is chased before it is abandoned as failed. */
        Duration reconcileWindow,

        /**
         * Charged when the purchase carries no number of its own.
         *
         * <p>Sandbox convenience only. Checkout collects the payer's own number,
         * so this is reached solely when a client omits it - handy for driving
         * the flow with curl, wrong everywhere else. A production deployment
         * that leaves it set bills one person for everybody else's purchases,
         * and the provider logs a warning every time it falls back.
         */
        String sandboxPayerMsisdn) {
}
