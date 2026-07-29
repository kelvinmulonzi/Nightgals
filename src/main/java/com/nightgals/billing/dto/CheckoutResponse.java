package com.nightgals.billing.dto;

import com.nightgals.billing.PaymentProvider;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A purchase, plus what the client should do to pay for it")
public record CheckoutResponse(
        PurchaseResponse purchase,
        @Schema(description = """
                How to complete payment:
                `REDIRECT` send the user to redirectUrl,
                `PROMPT_ON_PHONE` a prompt was pushed to their phone - poll the purchase,
                `MANUAL` show the instructions and wait for staff to confirm.
                """)
        PaymentProvider.PaymentInstruction.Action action,
        String redirectUrl,
        @Schema(description = "Human-readable payment steps, when action is MANUAL")
        String instructions) {
}
