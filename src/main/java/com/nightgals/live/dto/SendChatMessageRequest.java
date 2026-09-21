package com.nightgals.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "One message to post to a broadcast's chat")
public record SendChatMessageRequest(

        @Schema(example = "hey from Douala!!")
        @NotBlank(message = "Say something first")
        @Size(max = 300, message = "Keep it under 300 characters")
        String body) {
}
