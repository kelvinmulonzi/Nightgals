package com.nightgals.billing;

/**
 * Asks a payment provider what happened to a purchase that is still PENDING.
 *
 * <p>Exists because a webhook is not a guarantee. It can be unregistered,
 * pointed at an unreachable host, or signed with the wrong secret, and all three
 * look the same from the payer's side: money left their card and nothing
 * unlocked. Providers that can be asked directly implement this, and the answer
 * is authoritative in a way that "we have not been told yet" is not.
 *
 * <p>Implementations must be safe to call on demand and out of band: settling is
 * idempotent, so losing a race with a webhook or a scheduled sweep is a no-op.
 * They must also never fail a purchase for being old - abandoning one is a
 * scheduled decision, not something a payer's own status check should trigger.
 */
public interface PurchaseReconciler {

    /** Whether this reconciler speaks for the provider that took this payment. */
    boolean handles(Purchase purchase);

    /**
     * Brings one purchase up to date with the provider.
     *
     * @return true when the check reached the provider and applied its answer
     */
    boolean reconcileNow(Purchase purchase);
}
