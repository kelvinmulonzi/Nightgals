package com.nightgals.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What the caller has, in general.
 *
 * <p>Deliberately not a list of everything they own. Access is per item now, and
 * a viewer with two hundred unlocked videos does not want them enumerated on
 * every page load - each gallery reports its own `locked` flags instead.
 */
@Schema(description = "The caller's standing: trial, credit, and how much they own")
public record EntitlementResponse(

        @Schema(description = "True while the 7-day free trial is running - everything is open")
        boolean onTrial,
        @Schema(description = "When the trial ends") Instant trialEndsAt,

        @Schema(description = "How many items the caller has bought") long unlockedItems,

        @Schema(description = "Spendable referral credit, in minor units") long creditBalanceMinor,
        @Schema(example = "5000") String creditBalanceDisplay,
        @Schema(example = "XAF") String currency,

        @Schema(description = """
                The payment method a checkout gets when it names none - `MOMO`,
                `STRIPE`, `MANUAL` or `AUTO`.

                The **default**, not the only one: several run at once and the buyer
                chooses per purchase by sending `method`. For the full list, with
                labels to render, call `GET /api/v1/billing/payment-methods`.
                """, example = "MOMO")
        String paymentProvider,

        @Schema(description = """
                Whether checkout has to collect a Mobile Money number *for the default
                method above*. Methods that ignore the field are not broken by a client
                that always sends it.

                Only describes the default. A client that lets the buyer pick should
                read `requiresPayerMsisdn` from the payment-methods list instead, since
                the answer differs per method.
                """)
        boolean requiresPayerMsisdn) {
}
