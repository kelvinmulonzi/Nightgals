package com.nightgals.platform;

import com.nightgals.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The handful of settings a client needs before anybody has signed in.
 *
 * <p>Everything here is deployment configuration, not account data: it is the
 * same answer for every caller and reveals nothing about anyone. It exists
 * because the sign-up screen has to describe the journey ahead of it, and at
 * that point there is no session to read {@code /me} with.
 */
@Tag(name = "0. Platform", description = "Settings the client needs before sign-in")
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class PlatformConfigController {

    private final AppProperties appProperties;

    @Schema(description = "Deployment settings that shape the sign-up journey")
    public record PlatformConfigResponse(

            @Schema(description = """
                    Whether creators are asked for identity documents.

                    When false there is no document step and no review queue, so a
                    sign-up screen must not promise one - saving a profile is the
                    whole of onboarding.
                    """)
            boolean kycRequired,

            @Schema(description = "The age nobody below may register", example = "18")
            int minimumAge) {
    }

    @Operation(summary = "Read the public platform settings",
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "The settings")
    @GetMapping
    public PlatformConfigResponse config() {
        return new PlatformConfigResponse(appProperties.kycRequired(), appProperties.minimumAge());
    }
}
