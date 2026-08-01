package com.nightgals.billing.dto;

import com.nightgals.billing.CreatorPackageCode;
import com.nightgals.common.Money;
import com.nightgals.config.CreatorPackageProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;

@Schema(description = "One of the three packages a creator can subscribe to")
public record CreatorPackageResponse(

        @Schema(example = "BLACK_DIAMOND") CreatorPackageCode code,
        @Schema(example = "Black Diamond") String label,
        @Schema(example = "Maximum visibility and promotion") String tagline,

        @Schema(description = "Price in minor units. XAF has none, so this is the price.",
                example = "15000")
        long priceMinor,
        @Schema(example = "15000") String priceDisplay,
        @Schema(example = "XAF") String currency,

        @Schema(description = "How long it runs. Weekly.", example = "PT168H") Duration duration,

        @Schema(description = "Premium videos that may be posted at once", example = "10")
        int maxPremiumVideos,
        @Schema(description = "Photos that may be posted at once", example = "80")
        int maxPhotos,
        @Schema(description = "Minutes of live broadcast per day", example = "120")
        int liveMinutesPerDay,
        @Schema(description = "Live allowance written for people, e.g. '2 hours'", example = "2 hours")
        String liveAllowanceLabel,

        @Schema(description = "Placement in search and on the homepage. Higher wins.", example = "3")
        int searchPriority) {

    public static CreatorPackageResponse of(CreatorPackageCode code,
                                            CreatorPackageProperties.Package config,
                                            String currency) {
        return new CreatorPackageResponse(
                code,
                config.label(),
                config.tagline(),
                config.priceMinor(),
                Money.plain(config.priceMinor(), currency),
                currency,
                config.duration(),
                config.maxPremiumVideos(),
                config.maxPhotos(),
                config.liveMinutesPerDay(),
                liveLabel(config.liveMinutesPerDay()),
                config.searchPriority() != null ? config.searchPriority() : code.rank());
    }

    /** "120 minutes" is how a database says it; "2 hours" is how a person does. */
    private static String liveLabel(int minutes) {
        if (minutes <= 0) {
            return "Not included";
        }
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours == 1 ? "1 hour per day" : hours + " hours per day";
        }
        return minutes + " minutes per day";
    }
}
