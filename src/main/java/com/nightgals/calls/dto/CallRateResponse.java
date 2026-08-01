package com.nightgals.calls.dto;

import com.nightgals.calls.CallRate;
import com.nightgals.common.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "What one creator charges for a call of a given length")
public record CallRateResponse(
        UUID id,
        @Schema(example = "15") int durationMinutes,
        @Schema(example = "8000") long priceMinor,
        @Schema(example = "8000") String priceDisplay,
        @Schema(example = "XAF") String currency,
        boolean active) {

    public static CallRateResponse of(CallRate rate) {
        return new CallRateResponse(
                rate.getId(),
                rate.getDurationMinutes(),
                rate.getPriceMinor(),
                Money.plain(rate.getPriceMinor(), rate.getCurrency()),
                rate.getCurrency(),
                rate.isActive());
    }
}
