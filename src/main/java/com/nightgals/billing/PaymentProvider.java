package com.nightgals.billing;

/**
 * How a pending purchase gets paid for.
 *
 * <p>Deliberately narrow. The provider's job is to start a payment and hand back
 * whatever the client needs to complete it; settlement arrives later, out of
 * band, and calls {@link BillingService#settle}. Nothing about access control
 * lives here.
 *
 * <p>Several are live at once and the buyer picks between them per checkout -
 * MTN Mobile Money and cards through Stripe, in this market. {@link
 * PaymentProviders} does the resolving; a provider itself never knows the others
 * exist.
 */
public interface PaymentProvider {

    /**
     * Stored on the purchase, used to look it up when settlement arrives, and the
     * code a client sends as {@code method} to choose this one. One identifier
     * for all three, so a row in the database and a support conversation and an
     * API call are all naming the same thing.
     */
    String name();

    /** What the payment picker shows. */
    default String label() {
        return name();
    }

    /** A line under the label, when one helps. Null for none. */
    default String description() {
        return null;
    }

    /**
     * Other codes that select this provider.
     *
     * <p>Exists so a client may say {@code CARD} without having to know that the
     * card processor is Stripe - which is the platform's business, not the app's,
     * and could be swapped without a client release.
     */
    default java.util.Set<String> aliases() {
        return java.util.Set.of();
    }

    /**
     * Whether checkout has to collect a phone number for this provider.
     *
     * <p>Rendered by the picker to decide whether to show the field at all,
     * rather than the client hard-coding which methods want one.
     */
    default boolean requiresPayerMsisdn() {
        return false;
    }

    /**
     * A public key this provider's client-side SDK needs, if it has one.
     *
     * <p>Stripe's publishable key is the case in point: public by design, but
     * environment-specific, so serving it beside the method beats baking it into
     * an app build that then needs re-releasing to move between test and live.
     * Null for providers with nothing to publish.
     *
     * <p>Only ever a <i>public</i> credential. Nothing that can move money on its
     * own goes near this.
     */
    default String clientKey() {
        return null;
    }

    /**
     * Begins payment for a purchase that has just been created as PENDING.
     *
     * @return what the client should do next
     */
    PaymentInstruction startPayment(Purchase purchase);

    /**
     * Whether a purchase is paid the instant it is created.
     *
     * <p>False for every real provider: money arrives out of band, so settlement
     * is a later event. True only for {@link AutoSettlePaymentProvider}, which
     * exists so the product can be demonstrated end to end without a human
     * confirming every payment.
     */
    default boolean settlesImmediately() {
        return false;
    }

    /**
     * What the client needs to complete the payment.
     *
     * @param reference      the provider's id for this payment, if it has one yet
     * @param action         how the client proceeds: REDIRECT, PROMPT_ON_PHONE, MANUAL
     * @param redirectUrl    where to send the user, when action is REDIRECT
     * @param instructions   human-readable steps, when action is MANUAL
     */
    record PaymentInstruction(String reference, Action action, String redirectUrl, String instructions) {

        public enum Action {
            /** Send the user to redirectUrl. */
            REDIRECT,
            /** The provider has pushed a prompt to the user's phone; poll the purchase. */
            PROMPT_ON_PHONE,
            /** Pay out of band; a human will confirm. */
            MANUAL,
            /** Already paid by the time this response was written. Nothing to do, nothing to poll. */
            NONE
        }

        public static PaymentInstruction manual(String instructions) {
            return new PaymentInstruction(null, Action.MANUAL, null, instructions);
        }

        public static PaymentInstruction settled(String reference) {
            return new PaymentInstruction(reference, Action.NONE, null, null);
        }
    }
}
