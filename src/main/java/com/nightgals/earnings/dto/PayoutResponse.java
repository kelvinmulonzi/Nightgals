package com.nightgals.earnings.dto;

import com.nightgals.earnings.Payout;
import com.nightgals.earnings.PayoutMethod;
import com.nightgals.earnings.PayoutStatus;
import com.nightgals.common.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A payout request and its outcome")
public record PayoutResponse(
        UUID id,
        UUID creatorId,
        @Schema(description = "The creator's handle") String creatorUsername,
        long amountMinor,
        String amountDisplay,
        String currency,
        PayoutStatus status,
        PayoutMethod method,
        @Schema(description = """
                Masked for the creator, full for staff - an administrator needs the
                real number to send the money.
                """)
        String destination,
        String accountName,
        @Schema(description = "Transaction code recorded when the money was sent")
        String reference,
        String rejectionReason,
        Instant requestedAt,
        Instant processedAt,
        String processedByEmail) {

    public static PayoutResponse of(Payout p, boolean revealDestination) {
        return new PayoutResponse(
                p.getId(),
                p.getCreator().getId(),
                p.getCreator().getUsername(),
                p.getAmountMinor(),
                Money.plain(p.getAmountMinor(), p.getCurrency()),
                p.getCurrency(),
                p.getStatus(),
                p.getMethod(),
                revealDestination ? p.getDestination() : mask(p.getDestination()),
                p.getAccountName(),
                p.getReference(),
                p.getRejectionReason(),
                p.getRequestedAt(),
                p.getProcessedAt(),
                p.getProcessedBy() == null ? null : p.getProcessedBy().getEmail());
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }
}
