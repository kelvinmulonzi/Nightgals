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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Tag(name = "2. Profile", description = "The caller's own profile, and viewing other members")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final com.nightgals.views.ViewCounterService viewCounter;

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
                                            jakarta.servlet.http.HttpServletRequest request,
                                            @Parameter(description = "The member's user id")
                                            @PathVariable UUID userId) {
        ProfileResponse profile = profileService.getPublic(userId, AuthUser.userOrNull(principal));
        // After the profile has been resolved, never before: a view is only a
        // view once there was something to look at, and a 404 must not be counted
        // as interest in an account that is hidden or does not exist.
        viewCounter.record(com.nightgals.views.ViewSubject.PROFILE, userId,
                AuthUser.userOrNull(principal), userId, request);
        return profile;
    }

    @Operation(summary = "Set my profile picture",
            description = """
                    An image, kept with the profile rather than in the gallery. Setting one
                    does not publish a photo, does not use a package slot, and does not
                    appear among the things a viewer can buy.
                    """)
    @ApiResponse(responseCode = "200", description = "Updated profile, with the new photo URL")
    @ApiResponse(responseCode = "400", description = "Not an image, or too large",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/me/profile/photo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileResponse setPhoto(@AuthenticationPrincipal AuthUser principal,
                                    @Parameter(description = "The image", required = true)
                                    @RequestPart("file") MultipartFile file) {
        return profileService.setAvatar(principal.user(), file);
    }

    @Operation(summary = "Remove my profile picture")
    @ApiResponse(responseCode = "204", description = "Removed")
    @DeleteMapping("/me/profile/photo")
    public ResponseEntity<Void> removePhoto(@AuthenticationPrincipal AuthUser principal) {
        profileService.removeAvatar(principal.user());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Fetch a member's profile picture",
            description = "**Public.** 404 when they have not set one - show a placeholder.",
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "The image")
    @ApiResponse(responseCode = "404", description = "No picture set",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/members/{userId}/photo")
    public ResponseEntity<org.springframework.core.io.Resource> photo(@PathVariable UUID userId) {
        var avatar = profileService.avatar(userId);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(avatar.contentType()))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(avatar.resource());
    }
}
