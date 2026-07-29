package com.nightgals.user;

import com.nightgals.common.ErrorResponse;
import com.nightgals.user.dto.UsernameChangeRequest;
import com.nightgals.user.dto.UsernameResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "0b. Username", description = """
        Public handles.

        Members are known to each other by a generated handle like `VelvetFalcon482`,
        never by the legal name on their identity document. Verification proves who
        somebody is **to the platform**; it does not expose them to other members.

        A handle is assigned automatically at registration, so nobody has to choose
        one to get started.
        """)
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UsernameController {

    private final UsernameService usernameService;

    @Operation(
            summary = "Suggest available handles",
            description = """
                    Fresh random handles, each confirmed free at the moment of the call.
                    Backs a "roll again" button during onboarding.

                    Open without authentication so a signup screen can show options before
                    the account exists.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Suggested handles")
    @GetMapping("/usernames/suggestions")
    public Map<String, List<String>> suggestions(
            @Parameter(description = "How many to return, 1-10") @RequestParam(defaultValue = "5") int count) {
        return Map.of("suggestions", usernameService.suggest(count));
    }

    @Operation(summary = "My current handle")
    @ApiResponse(responseCode = "200", description = "The caller's handle")
    @GetMapping("/me/username")
    public UsernameResponse current(@AuthenticationPrincipal AuthUser principal) {
        return new UsernameResponse(principal.username(), null);
    }

    @Operation(
            summary = "Claim a specific handle",
            description = """
                    Changes the caller's public handle.

                    Rules: 3-30 characters, must start with a letter, letters/digits/underscores
                    only. Handles that impersonate the platform or its staff are refused.

                    Changes are rate-limited by a cooldown (30 days by default) so a handle
                    stays a stable identity rather than a disposable one. Correcting only the
                    capitalisation of your existing handle is free and does not start a cooldown.
                    """)
    @ApiResponse(responseCode = "200", description = "Handle updated")
    @ApiResponse(responseCode = "400", description = "Invalid format, or a reserved handle",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Already taken, or the cooldown has not elapsed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping("/me/username")
    public UsernameResponse change(@AuthenticationPrincipal AuthUser principal,
                                   @Valid @RequestBody UsernameChangeRequest request) {
        return new UsernameResponse(usernameService.change(principal.user(), request.username()), null);
    }

    @Operation(
            summary = "Replace my handle with a new random one",
            description = "For members who want a different pseudonym but do not want to pick one. Same cooldown applies.")
    @ApiResponse(responseCode = "200", description = "New handle assigned")
    @ApiResponse(responseCode = "409", description = "The cooldown has not elapsed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/me/username/reroll")
    public UsernameResponse reroll(@AuthenticationPrincipal AuthUser principal) {
        return new UsernameResponse(usernameService.reroll(principal.user()), null);
    }
}
