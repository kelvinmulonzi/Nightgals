package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Orange Money Cameroun's OMAPI, the other Mobile Money rail this market pays
 * with.
 *
 * <p>Structurally close to {@link MomoProperties}: this is a push, not a
 * redirect - {@link com.nightgals.billing.orange.OrangeClient} asks Orange to
 * prompt the payer's own handset, and approval is a later, separate event.
 * Two things make it its own shape rather than a copy of MTN's:
 *
 * <ol>
 *   <li>Starting a payment is <b>two calls</b>, not one - {@code mp/init} mints
 *       a {@code payToken}, then {@code mp/pay} spends it. Both need doing
 *       before anything is pushed to the payer.
 *   <li>Every call needs <b>two credentials at once</b>: a dynamic OAuth2
 *       bearer token ({@link #clientId}/{@link #clientSecret}, same shape as
 *       MTN) <i>and</i> a static {@link #staticAuthToken} issued once by Orange
 *       during onboarding and never rotated by this application. Missing
 *       either header fails the call the same way - there is no "half
 *       authenticated" response to tell them apart from.
 * </ol>
 *
 * <p>{@link #channelUserMsisdn} and {@link #channelPin} are this merchant's own
 * Orange Money identity - the equivalent of MTN's API user and key - and are
 * sent on every {@code mp/pay} call. They are never the payer's: the payer's
 * own number is {@code subscriberMsisdn}, collected at checkout the same way
 * MTN's is.
 */
@ConfigurationProperties(prefix = "nightgals.orange")
public record OrangeProperties(

        /** {@code https://api-s1.orange.cm} in both sandbox and production. */
        String baseUrl,

        /**
         * OAuth2 client credentials ("Clef du consommateur" / "Secret du
         * consommateur"), minted from the developer portal application - see the
         * Orange-supplied {@code Guide_Utilisateur_OMAPI} section 1.6.
         */
        String clientId,
        String clientSecret,

        /**
         * Issued once by Orange over email during onboarding, not through the
         * developer portal, and not rotated by this application - so unlike the
         * bearer token there is nothing here to refresh. Required on every
         * {@code omcoreapis} call alongside the bearer token; a request carrying
         * only one of the two is rejected the same way as carrying neither.
         */
        String staticAuthToken,

        /**
         * This merchant's own Orange Money number - the "channel" - sent on
         * every {@code mp/pay} call. In sandbox this is a fixed test value
         * Orange assigns ({@code 691301143}), not anything a payer supplies.
         */
        String channelUserMsisdn,

        /**
         * The PIN belonging to {@link #channelUserMsisdn}, sent alongside it.
         * This authenticates the <em>merchant's</em> channel, not the payer - it
         * never touches whatever the payer approves the push with on their own
         * handset. Treated the same as a password: never logged.
         */
        String channelPin,

        /**
         * Where Orange POSTs the outcome, sent per-request as {@code notifUrl}
         * rather than registered once - so it travels with every {@code mp/pay}
         * call instead of living only in configuration. Must be public HTTPS;
         * blank on a laptop, where {@link com.nightgals.billing.orange.OrangeReconciler}
         * alone is what settles things.
         */
        String notifUrl,

        /**
         * Charged when a purchase carries no number of its own.
         *
         * <p>Sandbox convenience only, same reasoning as {@link
         * MomoProperties#sandboxPayerMsisdn()}: checkout does not yet collect the
         * payer's own Orange Money number, so this is reached whenever a client
         * omits one. A production deployment that leaves it set bills one person
         * for everybody else's purchases, and the provider logs a warning every
         * time it falls back.
         */
        String sandboxPayerMsisdn,

        /** OAuth2 tokens last about an hour; renew this early to avoid racing expiry. */
        Duration tokenRefreshMargin,

        /**
         * Sweep that asks Orange about purchases still PENDING.
         *
         * <p>Not an optimisation, same reasoning as {@link MomoProperties}: the
         * notification is not retried forever and usually has nowhere to land in
         * local development at all. Without this sweep a lost notification means
         * a viewer paid and nothing unlocked.
         */
        String reconcileCron,

        /** How long a PENDING purchase is chased before it is abandoned as failed. */
        Duration reconcileWindow,

        /**
         * Longest any single call to Orange may take to connect, and again to
         * answer. A timed-out call is treated as Orange being unavailable, not as
         * a failure - checkout says so, and the next sweep simply asks again.
         */
        Duration timeout) {
}
