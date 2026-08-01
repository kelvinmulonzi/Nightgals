package com.nightgals.billing.dto;

import com.nightgals.billing.CreatorPackageCode;
import com.nightgals.billing.Purchase;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.billing.PurchaseType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A payment attempt")
public record PurchaseResponse(
        UUID id,
        PurchaseType type,
        @Schema(description = "Who is being unlocked, for PROFILE_UNLOCK") UUID targetUserId,
        @Schema(description = "Which plan, for SUBSCRIPTION") String planCode,
        @Schema(description = "Which package, for CREATOR_PACKAGE") CreatorPackageCode packageCode,
        long amountMinor,
        String priceDisplay,
        String currency,
        PurchaseStatus status,
        String provider,
        @Schema(description = "Only present once the purchase has failed") String failureReason,
        Instant completedAt,
        Instant createdAt) {

    public static PurchaseResponse of(Purchase p) {
        return new PurchaseResponse(
                p.getId(),
                p.getType(),
                p.getTargetUser() == null ? null : p.getTargetUser().getId(),
                p.getPlanCode(),
                p.getPackageCode(),
                p.getAmountMinor(),
                String.format("%.2f", p.getAmountMinor() / 100.0),
                p.getCurrency(),
                p.getStatus(),
                p.getProvider(),
                p.getFailureReason(),
                p.getCompletedAt(),
                p.getCreatedAt());
    }
}
