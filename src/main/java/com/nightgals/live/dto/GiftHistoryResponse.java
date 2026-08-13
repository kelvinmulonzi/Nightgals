package com.nightgals.live.dto;

import com.nightgals.common.Money;
import com.nightgals.live.Gift;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One gift, as it should read outside the broadcast it was sent in.
 *
 * <p>Separate from {@link GiftResponse} because the two answer different
 * questions. On the overlay everyone already knows whose room they are in, so a
 * gift is "who sent it". In a history it is a transaction, and the side reading
 * it needs the other end named - a sender's list of "🌹 Rose 500" with no
 * recipient and no room is not a record of anything.
 */
@Schema(description = "One gift, as a line in somebody's history")
public record GiftHistoryResponse(

        UUID id,

        @Schema(description = "Who sent it") UUID senderId,
        @Schema(example = "AmberSwallow863") String senderUsername,

        @Schema(description = "Who received it") UUID creatorId,
        @Schema(example = "RadiantPrism929") String creatorUsername,

        @Schema(description = "The broadcast it was sent in") UUID sessionId,
        @Schema(example = "Friday night") String sessionTitle,

        @Schema(example = "ROSE") String giftCode,
        @Schema(example = "Rose") String giftLabel,
        @Schema(example = "🌹") String giftIcon,

        @Schema(description = """
                What the sender paid, in minor units. Gross: the creator keeps this
                less the platform's commission, and what she can actually withdraw is
                on her earnings ledger rather than here.
                """, example = "500")
        long amountMinor,

        @Schema(example = "500") String amountDisplay,
        @Schema(example = "XAF") String currency,

        @Schema(description = "Optional note from the sender") String message,

        Instant sentAt) {

    public static GiftHistoryResponse of(Gift gift) {
        return new GiftHistoryResponse(
                gift.getId(),
                gift.getSender().getId(),
                gift.getSender().getUsername(),
                gift.getCreator().getId(),
                gift.getCreator().getUsername(),
                gift.getLiveSession().getId(),
                gift.getLiveSession().getTitle(),
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
