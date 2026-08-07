package com.nightgals.billing.dto;

import com.nightgals.billing.PaymentProvider;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One way to pay, as the checkout picker should render it")
public record PaymentMethodResponse(

        @Schema(description = "Send this back as `method` on a checkout call",
                example = "MOMO")
        String code,

        @Schema(description = "What to show on the button", example = "MTN Mobile Money")
        String label,

        @Schema(description = "A line under the label, when there is one to show",
                example = "A prompt is sent to your phone to approve.")
        String description,

        @Schema(description = """
                Whether choosing this one means also collecting `payerMsisdn`.
                Render the phone-number field off this rather than hard-coding
                which methods want one - it moves when a provider is swapped.
                """)
        boolean requiresPayerMsisdn,

        @Schema(description = "True for the method used when a checkout names none")
        boolean isDefault,

        @Schema(description = """
                The public key this method's client SDK needs, when it has one -
                Stripe's publishable key, for instance. Absent otherwise, and never
                a secret: the hosted-checkout flow needs nothing from it, and it is
                served here so that moving between test and live keys is a server
                setting rather than an app release.
                """, example = "pk_live_51U0nIHCC...")
        String clientKey) {

    public static PaymentMethodResponse of(PaymentProvider provider, boolean isDefault) {
        return new PaymentMethodResponse(
                provider.name(),
                provider.label(),
                provider.description(),
                provider.requiresPayerMsisdn(),
                isDefault,
                provider.clientKey());
    }
}
