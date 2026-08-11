package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Stripe Checkout, which is how anybody paying by card pays.
 *
 * <p>Three keys, and they are not interchangeable. The <b>publishable</b> key is
 * public by design - it identifies the account to a browser and is safe in a
 * client bundle. The <b>secret</b> key is the account: anything holding it can
 * move money, so it belongs in the environment and never in the repository. The
 * <b>webhook signing secret</b> is separate again, issued per endpoint when the
 * endpoint is registered, and without it settlement cannot be trusted at all -
 * see {@link com.nightgals.billing.stripe.StripeWebhookController}.
 *
 * <p>Test keys carry {@code _test_} and live keys {@code _live_}, and they
 * address entirely separate worlds: a live webhook secret against test keys
 * verifies nothing, and test cards decline against live keys. Move all three
 * together or none of them.
 */
@ConfigurationProperties(prefix = "nightgals.stripe")
public record StripeProperties(

        /** {@code sk_test_...} or {@code sk_live_...}. Never logged, never returned. */
        String secretKey,

        /**
         * {@code pk_test_...} or {@code pk_live_...}.
         *
         * <p>Safe to hand to a client, and handed to one: the hosted-checkout flow
         * does not need it, but a client that later renders Stripe's own elements
         * will, so it is served from the payment-methods endpoint rather than
         * pasted into an app build that then has to be re-released to change it.
         */
        String publishableKey,

        /**
         * {@code whsec_...}, from the endpoint's page in the Stripe dashboard.
         *
         * <p>Not optional. An unsigned webhook is an unauthenticated request that
         * grants paid content, so the handler refuses every call while this is
         * blank rather than falling back to trusting the body.
         */
        String webhookSecret,

        /**
         * Where Stripe returns the payer after a completed or abandoned payment.
         *
         * <p>{@code {purchaseId}} is substituted, so the destination can poll the
         * right purchase. On mobile these are deep links -
         * {@code nightgals://checkout/done?purchase={purchaseId}} - and on web
         * ordinary URLs. Returning here proves nothing about payment: the page
         * must still poll, because a payer can reach it by pressing back.
         */
        String successUrl,
        String cancelUrl,

        /**
         * What {@link #successUrl} and {@link #cancelUrl} are built from when
         * either is left blank.
         *
         * <p>Exists because a placeholder default only applies when the variable
         * is <em>absent</em>: {@code STRIPE_SUCCESS_URL=} in an env file is
         * present and empty, which beats {@code ${STRIPE_SUCCESS_URL:...}} and
         * leaves the URL empty rather than defaulted. Deriving the fallback in
         * code instead means a blank behaves the way everyone reads it.
         */
        String returnBaseUrl,

        /**
         * How long the hosted page stays payable.
         *
         * <p>Stripe permits 30 minutes to 24 hours and rejects anything outside
         * that, so {@link com.nightgals.billing.stripe.StripeGateway} clamps rather
         * than passing a misconfiguration through to a failed checkout.
         */
        Duration sessionTtl,

        /**
         * Sweep that asks Stripe about purchases still PENDING.
         *
         * <p>The same reasoning as the Mobile Money one: webhooks are retried but
         * not guaranteed, and a payer whose card was charged while nothing
         * unlocked is the worst outcome this system has.
         */
        String reconcileCron,

        /**
         * How long a PENDING purchase is chased before being abandoned as failed.
         *
         * <p>Must exceed {@link #sessionTtl} - a session Stripe still considers
         * payable must not be failed underneath the payer. The sweep mostly
         * settles on session status well before this matters.
         */
        Duration reconcileWindow,

        /**
         * Whether Stripe acts as merchant of record, calculating and remitting
         * sales tax itself.
         *
         * <p>Off, and deliberately. Newer accounts have Managed Payments switched
         * on by default, and it requires every line item to carry a Stripe tax
         * code - so leaving it on rejects each checkout with
         * {@code the product tax code is missing} rather than opening a page. It
         * also has eligibility rules this platform's content does not obviously
         * meet.
         *
         * <p>Switching it on means classifying every purchase type with a
         * {@code txcd_} code first; until then the platform settles tax itself,
         * which is what it did before Managed Payments existed.
         */
        boolean managedPayments) {
}
