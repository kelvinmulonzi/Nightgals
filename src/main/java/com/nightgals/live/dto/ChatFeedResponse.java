package com.nightgals.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Chat messages sent since the client last asked")
public record ChatFeedResponse(

        @Schema(description = "Oldest first, which is the order they were sent in")
        List<ChatMessageResponse> messages,

        @Schema(description = """
                Send this back as `since` on the next poll. It is the server's clock at
                the moment of reading, not the last message's timestamp - see
                GiftFeedResponse.until for why.""")
        Instant until) {
}
