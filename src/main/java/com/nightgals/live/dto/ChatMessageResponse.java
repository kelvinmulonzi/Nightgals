package com.nightgals.live.dto;

import com.nightgals.live.LiveChatMessage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One chat message, as it should appear on the broadcast")
public record ChatMessageResponse(

        UUID id,

        @Schema(description = "Who sent it") UUID senderId,

        @Schema(description = "Their handle, so the overlay can name them", example = "AmberSwallow863")
        String senderUsername,

        @Schema(example = "hey from Douala!!") String body,

        @Schema(description = "When it was sent. Feed order is by this.")
        Instant sentAt) {

    public static ChatMessageResponse of(LiveChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getSender().getId(),
                message.getSender().getUsername(),
                message.getBody(),
                message.getCreatedAt());
    }
}
