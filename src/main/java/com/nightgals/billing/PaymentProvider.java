package com.nightgals.billing;

/**
 * How a pending purchase gets paid for.
 *
 * <p>Deliberately narrow. The provider's job is to start a payment and hand back
 * whatever the client needs to complete it; settlement arrives later, out of
 * band, and calls {@link BillingService#settle}. Nothing about access control
 * lives here.
 *
 * <p>The intended first real implementation is M-Pesa via the Daraja STK-push
 * API: {@code startPayment} triggers the push, the callback URL settles the
 * purchase.
 */
public interface PaymentProvider {

    /** Stored on the purchase, and used to look it up when settlement arrives. */
    String name();

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
