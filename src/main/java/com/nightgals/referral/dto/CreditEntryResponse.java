package com.nightgals.referral.dto;

import com.nightgals.common.Money;
import com.nightgals.referral.CreditEntry;
import com.nightgals.referral.CreditReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One movement of account credit. Positive is in, negative is out.")
public record CreditEntryResponse(
        UUID id,
        long amountMinor,
        @Schema(example = "5000") String amountDisplay,
        String currency,
        CreditReason reason,
        @Schema(description = "Whose first purchase earned it, for a referral bonus")
        String referredUsername,
        String note,
        Instant createdAt) {

    public static CreditEntryResponse of(CreditEntry e) {
        return new CreditEntryResponse(
                e.getId(),
                e.getAmountMinor(),
                Money.plain(e.getAmountMinor(), e.getCurrency()),
                e.getCurrency(),
                e.getReason(),
                e.getReferredUser() == null ? null : e.getReferredUser().getUsername(),
                e.getNote(),
                e.getCreatedAt());
    }
}
