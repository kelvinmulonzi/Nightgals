package com.nightgals.billing;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Treats every purchase as paid the moment it is created.
 *
 * <p><b>No money changes hands.</b> This is the happy path: it exists so the
 * product can be walked end to end - unlock a creator, buy a package, watch the
 * content appear - without an administrator confirming each payment by hand.
 *
 * <p>It is a stand-in for a real provider, not a shortcut around one. Running it
 * in production would give away every paid thing on the platform for free, so it
 * announces itself loudly at startup and is switched by an explicit property.
 * Wiring in M-Pesa Daraja or a card gateway means adding a
 * {@link PaymentProvider} and pointing {@code nightgals.monetization.provider}
 * at it; nothing else in the codebase changes.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nightgals.monetization.provider", havingValue = "auto",
        matchIfMissing = true)
public class AutoSettlePaymentProvider implements PaymentProvider {

    @PostConstruct
    void warn() {
        log.warn("""

                ****************************************************************
                *  PAYMENTS ARE AUTO-SETTLED. No money is being collected.     *
                *  Every unlock and package completes instantly and for free.  *
                *  Set nightgals.monetization.provider=manual, or integrate a  *
                *  real PaymentProvider, before this faces real users.         *
                ****************************************************************""");
    }

    @Override
    public String name() {
        return "AUTO";
    }

    @Override
    public PaymentInstruction startPayment(Purchase purchase) {
        log.info("Auto-settling purchase {} ({} {} minor units) - no payment was collected",
                purchase.getId(), purchase.getCurrency(), purchase.getAmountMinor());
        // A reference is still generated, so the purchase looks the same shape as
        // one a real provider settled and the admin views read normally.
        return PaymentInstruction.settled("AUTO-" + UUID.randomUUID());
    }

    @Override
    public boolean settlesImmediately() {
        return true;
    }
}
