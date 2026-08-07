package com.nightgals.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * How the buyer wants to pay, for a purchase that carries no details of its own.
 *
 * <p>The body may be omitted entirely, in which case the deployment's default
 * method is used and nothing is collected - which is what every client written
 * before the payment picker does.
 */
@Schema(description = "Optional payment details")
public record CheckoutRequest(

        @Schema(description = """
                Which payment method to use, from `GET /api/v1/billing/payment-methods`.
                `MOMO` for MTN Mobile Money, `STRIPE` (or its alias `CARD`) for a card.

                Omit it to use the platform default, which is what clients written
                before the picker existed do.
                """, example = "MOMO")
        String method,

        @Schema(description = """
                The Mobile Money number to charge, in international format. A leading
                plus and any spacing are accepted and stripped, so both `+237 689 686 224`
                and `237689686224` are the same number.

                Required when `method` is a mobile-money one, ignored otherwise - so
                a client that always sends it is correct either way. Which methods
                need it is reported by `requiresPayerMsisdn` on the payment-methods
                list rather than being something to hard-code.
                """, example = "237689686224")
        @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{7,19}$",
                message = "must be a phone number in international format")
        String payerMsisdn) {
}
