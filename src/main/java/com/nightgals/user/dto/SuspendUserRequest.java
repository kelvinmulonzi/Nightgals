package com.nightgals.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why an account is being burned.
 *
 * <p>The reason is required. A moderation action with no stated cause cannot be
 * reviewed by anyone afterwards - including the person who took it - and this is
 * an action that takes somebody's work off the site.
 */
public record SuspendUserRequest(

        @Schema(description = "Why. Shown to other staff in the console, never to the member.",
                example = "Posted content involving a third party who has not consented")
        @NotBlank
        @Size(max = 500)
        String reason) {
}
