package com.nightgals.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * Optional payment details for a purchase that carries none of its own.
 *
 * <p>The body may be omitted entirely: providers that settle instantly or by
 * hand have nothing to collect. It exists for mobile money, where a prompt has
 * to be sent somewhere.
 */
@Schema(description = "Optional payment details")
public record CheckoutRequest(

        @Schema(description = """
                The Mobile Money number to charge, in international format. A leading
                plus and any spacing are accepted and stripped, so both `+237 689 686 224`
                and `237689686224` are the same number.

                Required when the platform is on a mobile-money provider, ignored
                otherwise - so a client that always sends it is correct either way.
                """, example = "237689686224")
        @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{7,19}$",
                message = "must be a phone number in international format")
        String payerMsisdn) {
}
