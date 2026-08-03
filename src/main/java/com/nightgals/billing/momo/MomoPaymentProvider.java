package com.nightgals.billing.momo;

import com.nightgals.billing.PaymentProvider;
import com.nightgals.billing.Purchase;
import com.nightgals.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MTN Mobile Money collections.
 *
 * <p>Selected with {@code nightgals.monetization.provider=momo}.
 *
 * <p>Nothing settles here. {@code requesttopay} only pushes a prompt to the
 * payer's handset; whether they approve it is a later, separate event that
 * arrives as a callback or is discovered by {@link MomoReconciler}. So the
 * purchase stays PENDING and the client polls it - which is exactly the contract
 * {@code ManualPaymentProvider} already has, minus the human.
 *
 * <p>The purchase id doubles as the MoMo {@code X-Reference-Id}. One id for the
 * whole payment means retrying a checkout cannot produce two prompts, and a
 * callback needs no lookup table to find its purchase.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nightgals.monetization.provider", havingValue = "momo")
public class MomoPaymentProvider implements PaymentProvider {

    private final MomoClient client;
    private final com.nightgals.config.MomoProperties properties;

    @Override
    public String name() {
        return "MOMO";
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
            // Worth failing loudly rather than falling back to manual: a viewer
            // who is told "we will confirm by hand" when the real problem is a
            // missing phone number waits for something nobody is doing.
            throw ApiException.badRequest("msisdn_required",
                    "A Mobile Money number is required to pay for this.");
        }

        // Record what was actually charged, not what was asked for. When the
        // fallback above supplies the number, this is the only place it is
        // written down - and a dispute needs the handset that really paid.
        purchase.setPayerMsisdn(msisdn);

        boolean accepted = client.requestToPay(
                purchase.getId(),
                msisdn,
                purchase.getAmountMinor(),
                purchase.getId().toString(),
                "Nightgals " + purchase.getType().name().toLowerCase().replace('_', ' '));

        if (!accepted) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "momo_unavailable",
                    "Mobile Money is not responding. Try again in a moment.");
        }

        log.info("MoMo prompt sent for purchase {} to {}", purchase.getId(), masked(msisdn));

        return new PaymentInstruction(
                purchase.getId().toString(),
                PaymentInstruction.Action.PROMPT_ON_PHONE,
                null,
                "Check your phone and approve the Mobile Money request.");
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
