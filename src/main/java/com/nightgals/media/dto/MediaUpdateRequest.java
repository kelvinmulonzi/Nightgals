package com.nightgals.media.dto;

import com.nightgals.media.ContentTier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "Editable fields on an existing media item")
public record MediaUpdateRequest(

        @Schema(example = "Saturday at Alchemist") @Size(max = 300) String caption,

        @Schema(description = "Display order, lowest first") @Min(0) Integer position,

        @Schema(description = """
                Move this item between the shop window and the paywall. `FREE` is visible
                to everyone including anonymous visitors; `EXCLUSIVE` needs a paying viewer.

                The profile picture cannot be made exclusive - make another photo primary first.
                """)
        ContentTier tier,

        @Schema(description = "Make this the main profile photo. Photos only, and it becomes FREE.")
        Boolean primary) {
}
