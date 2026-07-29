package com.nightgals.earnings.dto;

import com.nightgals.earnings.Earning;
import com.nightgals.earnings.EarningStatus;
import com.nightgals.earnings.EarningType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One line of a creator's ledger")
public record EarningResponse(
        UUID id,
        EarningType type,
        @Schema(description = "What the viewer paid") long grossMinor,
        @Schema(description = "The platform's cut") long commissionMinor,
        @Schema(description = "What the creator keeps") long netMinor,
        String netDisplay,
        String currency,
        EarningStatus status,
        @Schema(description = "Attribution month for a subscription share", example = "2026-07")
        String period,
        @Schema(description = "When this becomes payable") Instant availableAt,
        String note,
        Instant createdAt) {

    public static EarningResponse of(Earning e) {
        return new EarningResponse(
                e.getId(),
                e.getType(),
                e.getGrossMinor(),
                e.getCommissionMinor(),
                e.getNetMinor(),
                String.format("%.2f", e.getNetMinor() / 100.0),
                e.getCurrency(),
                e.getStatus(),
                e.getPeriod(),
                e.getAvailableAt(),
                e.getNote(),
                e.getCreatedAt());
    }
}
