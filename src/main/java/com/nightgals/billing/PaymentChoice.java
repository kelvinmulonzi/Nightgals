package com.nightgals.billing;

/**
 * How the buyer said they want to pay.
 *
 * <p>A record rather than two more string parameters on every checkout method:
 * the pair travels together from the controller to the provider, and adding a
 * third detail later - a saved card, a return URL - should not mean editing five
 * signatures again.
 *
 * @param method      a {@link PaymentProvider#name()} or alias; blank means the
 *                    deployment's default
 * @param payerMsisdn the handset to prompt, for mobile money; ignored otherwise
 */
public record PaymentChoice(String method, String payerMsisdn) {

    private static final PaymentChoice DEFAULT = new PaymentChoice(null, null);

    /** Whatever the deployment considers its default method, with no details. */
    public static PaymentChoice none() {
        return DEFAULT;
    }

    public static PaymentChoice of(String method, String payerMsisdn) {
        return method == null && payerMsisdn == null ? DEFAULT : new PaymentChoice(method, payerMsisdn);
    }
}
