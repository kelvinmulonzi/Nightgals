package com.nightgals.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Gifts sent since the client last asked, and the running total")
public record GiftFeedResponse(

        @Schema(description = "Oldest first, which is the order they happened in")
        List<GiftResponse> gifts,

        @Schema(description = """
                Send this back as `since` on the next poll. It is the server's clock at
                the moment of reading, not the last gift's timestamp - a quiet broadcast
                would otherwise keep asking from the same old point, and the client's own
                clock would replay or skip gifts whenever the two disagree.""")
        Instant until,

        @Schema(description = "Everything gifted to this broadcast so far, in minor units",
                example = "12500")
        long totalMinor,

        @Schema(example = "12500") String totalDisplay,

        @Schema(example = "XAF") String currency) {
}
