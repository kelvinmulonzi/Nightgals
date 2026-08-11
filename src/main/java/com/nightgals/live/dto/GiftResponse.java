package com.nightgals.live.dto;

import com.nightgals.common.Money;
import com.nightgals.live.Gift;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One gift that was sent, as it should appear on the broadcast")
public record GiftResponse(

        UUID id,

        @Schema(description = "Who sent it") UUID senderId,

        @Schema(description = "Their handle, so the overlay can name them", example = "AmberSwallow863")
        String senderUsername,

        @Schema(example = "ROSE") String giftCode,

        @Schema(example = "Rose") String giftLabel,

        @Schema(example = "🌹") String giftIcon,

        @Schema(description = "What it was worth when sent, in minor units", example = "500")
        long amountMinor,

        @Schema(example = "500") String amountDisplay,

        @Schema(example = "XAF") String currency,

        @Schema(description = "Optional note from the sender", example = "keep going!")
        String message,

        @Schema(description = "When it was sent. Feed order is by this.")
        Instant sentAt) {

    public static GiftResponse of(Gift gift) {
        return new GiftResponse(
                gift.getId(),
                gift.getSender().getId(),
                gift.getSender().getUsername(),
                gift.getGiftCode(),
                gift.getGiftLabel(),
                gift.getGiftIcon(),
                gift.getAmountMinor(),
                Money.plain(gift.getAmountMinor(), gift.getCurrency()),
                gift.getCurrency(),
                gift.getMessage(),
                gift.getCreatedAt());
    }
}
