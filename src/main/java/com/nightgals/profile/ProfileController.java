package com.nightgals.profile;

import com.nightgals.common.ErrorResponse;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.profile.dto.ProfileResponse;
import com.nightgals.user.AuthUser;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "2. Profile", description = "The caller's own profile, and viewing other members")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @Operation(summary = "Get my profile")
    @ApiResponse(responseCode = "200", description = "The caller's profile")
    @ApiResponse(responseCode = "404", description = "Profile not created yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/me/profile")
    public ProfileResponse getMyProfile(@AuthenticationPrincipal AuthUser principal) {
        return profileService.getOwn(principal.id());
    }

    @Operation(
            summary = "Create or update my profile",
            description = """
                    Idempotent: creates the profile the first time and replaces it on later calls.

                    **A viewer account becomes a creator account here.** Filling in a public
                    profile is unambiguous intent, so there is no need to call
                    `POST /api/v1/me/become-creator` first - though you still can, if the
                    client wants an explicit "start creating" step.

                    The user must be at least 18. Once a KYC submission is pending or approved the
                    date of birth is frozen, because that is the field a reviewer checked against
                    the identity document.
                    """)
    @ApiResponse(responseCode = "200", description = "Profile saved")
    @ApiResponse(responseCode = "400", description = "Validation failed, or the user is under 18",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Date of birth is locked by verification",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping("/me/profile")
    public ProfileResponse upsertMyProfile(@AuthenticationPrincipal AuthUser principal,
                                           @Valid @RequestBody ProfileRequest request) {
        return profileService.createOrUpdate(principal.user(), request);
    }

    @Operation(
            summary = "View a creator's profile",
            description = """
                    **Public - no sign-in required.**

                    Only approved, discoverable creators are visible; anyone else returns 404.
                    The public view withholds the private nickname and the exact date of birth,
                    exposing only the handle and an age.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "The member's public profile")
    @ApiResponse(responseCode = "404", description = "No such member, or they are not verified/discoverable",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/members/{userId}/profile")
    public ProfileResponse getMemberProfile(@AuthenticationPrincipal AuthUser principal,
                                            @Parameter(description = "The member's user id")
                                            @PathVariable UUID userId) {
        return profileService.getPublic(userId, AuthUser.userOrNull(principal));
    }
}
