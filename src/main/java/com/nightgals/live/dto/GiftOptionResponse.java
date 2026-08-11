package com.nightgals.live.dto;

import com.nightgals.common.Money;
import com.nightgals.config.GiftProperties;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One sendable gift, as the picker renders it")
public record GiftOptionResponse(

        @Schema(description = "What to send back when this one is chosen", example = "ROSE")
        String code,

        @Schema(example = "Rose") String label,

        @Schema(description = "An emoji, so no image has to be fetched", example = "🌹")
        String icon,

        @Schema(description = "What it costs the sender, in minor units", example = "500")
        long priceMinor,

        @Schema(example = "500") String priceDisplay,

        @Schema(example = "XAF") String currency) {

    public static GiftOptionResponse of(GiftProperties.Item item, String currency) {
        return new GiftOptionResponse(
                item.code(),
                item.label(),
                item.icon(),
                item.priceMinor(),
                Money.plain(item.priceMinor(), currency),
                currency);
    }
}
