package com.nightgals.billing;

import com.nightgals.common.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The provider used until a real payment API is integrated.
 *
 * <p>It does not pretend a payment happened. The purchase stays PENDING and an
 * administrator settles it from the admin endpoints once money has actually
 * arrived - which is how a till-number or bank-transfer flow works anyway, so
 * this is usable in production, not only a placeholder.
 *
 * <p>Enabled by listing {@code manual} in {@code nightgals.monetization.providers}.
 * The fallback when nothing is configured is {@link AutoSettlePaymentProvider},
 * which needs no human at all - see there for why that is only acceptable before
 * launch.
 */
@Slf4j
@Component
@ConditionalOnPaymentProvider("manual")
public class ManualPaymentProvider implements PaymentProvider {

    @Value("${nightgals.monetization.manual-payment-instructions:}")
    private String configuredInstructions;

    @Override
    public String name() {
        return "MANUAL";
    }

    @Override
    public String label() {
        return "Bank transfer or till";
    }

    @Override
    public String description() {
        return "Pay out of band; access opens once our team confirms receipt.";
    }

    @Override
    public PaymentInstruction startPayment(Purchase purchase) {
        log.info("Purchase {} created as PENDING for manual settlement ({} {} minor units)",
                purchase.getId(), purchase.getCurrency(), purchase.getAmountMinor());

        String instructions = configuredInstructions == null || configuredInstructions.isBlank()
                ? ("Payment is not yet automated. Send " + formatAmount(purchase)
                   + " and quote reference " + purchase.getId()
                   + ". Access is granted once our team confirms receipt.")
                : configuredInstructions.replace("{amount}", formatAmount(purchase))
                                        .replace("{reference}", purchase.getId().toString());

        return PaymentInstruction.manual(instructions);
    }

    private String formatAmount(Purchase purchase) {
        return Money.withCurrency(purchase.getAmountMinor(), purchase.getCurrency());
    }
}
