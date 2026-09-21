package com.nightgals.billing.orange;

import com.nightgals.billing.ConditionalOnPaymentProvider;
import com.nightgals.billing.PaymentProvider;
import com.nightgals.billing.Purchase;
import com.nightgals.common.ApiException;
import com.nightgals.config.OrangeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Orange Money Cameroun, the other Mobile Money rail this market pays with.
 *
 * <p>Enabled by listing {@code orange} in {@code nightgals.monetization.providers},
 * and chosen per checkout by a client sending {@code method: ORANGE}. Same shape
 * as {@link com.nightgals.billing.momo.MomoPaymentProvider}: a prompt goes to the
 * payer's own handset and whether they approve it is a later, separate event -
 * so the purchase stays PENDING and the client polls it.
 *
 * <p>Unlike MTN, starting the payment mints Orange's own {@code payToken} rather
 * than reusing the purchase id as the reference - {@code BillingService} stores
 * whatever {@link #startPayment} returns as {@link Purchase#getProviderReference()},
 * and {@link OrangeCallbackController} and {@link OrangeReconciler} both read it
 * back from there for every later status check.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnPaymentProvider("orange")
public class OrangePaymentProvider implements PaymentProvider {

    private final OrangeClient client;
    private final OrangeProperties properties;

    @Override
    public String name() {
        return "ORANGE";
    }

    @Override
    public String label() {
        return "Orange Money";
    }

    @Override
    public String description() {
        return "A prompt is sent to your phone to approve.";
    }

    /** The whole point of the method: there is a handset to push a prompt to. */
    @Override
    public boolean requiresPayerMsisdn() {
        return true;
    }

    @Override
    public PaymentInstruction startPayment(Purchase purchase) {
        String msisdn = purchase.getPayerMsisdn();
        if (msisdn == null || msisdn.isBlank()) {
            // Sandbox scaffolding: nothing collects a number from the viewer yet.
            msisdn = properties.sandboxPayerMsisdn();
            if (msisdn != null && !msisdn.isBlank()) {
                log.warn("Purchase {} has no payer number - charging the configured "
                        + "sandbox number instead. This must not happen in production.",
                        purchase.getId());
            }
        }
        if (msisdn == null || msisdn.isBlank()) {
            throw ApiException.badRequest("msisdn_required",
                    "An Orange Money number is required to pay for this.");
        }

        // Record what was actually charged, not what was asked for - same
        // reasoning as MomoPaymentProvider: a dispute needs the handset that
        // really paid, and this is the only place the fallback is written down.
        purchase.setPayerMsisdn(msisdn);

        String payToken = client.requestToPay(purchase, msisdn).orElse(null);
        if (payToken == null) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "orange_unavailable",
                    "Orange Money is not responding. Try again in a moment.");
        }

        log.info("Orange prompt sent for purchase {} to {}", purchase.getId(), masked(msisdn));

        return new PaymentInstruction(
                payToken,
                PaymentInstruction.Action.PROMPT_ON_PHONE,
                null,
                "Check your phone and approve the Orange Money request.");
    }

    /** Never true. Approval happens on a handset, on the payer's schedule. */
    @Override
    public boolean settlesImmediately() {
        return false;
    }

    private static String masked(String msisdn) {
        return msisdn.length() <= 4 ? "****" : "****" + msisdn.substring(msisdn.length() - 4);
    }
}
