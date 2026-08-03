package com.nightgals.media;

import com.nightgals.common.ErrorResponse;
import com.nightgals.media.ContentTier;
import com.nightgals.media.dto.MediaResponse;
import com.nightgals.media.dto.MediaUpdateRequest;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name = "5. Media", description = """
        Photos and video on a member's profile.

        **Uploading requires `verificationStatus = APPROVED`.** Until an administrator has
        matched the creator's face to their government ID, every upload returns
        `403 verification_required`.

        That is the only gate. Once through it, uploads publish immediately - there is
        no review queue. Moderators can remove an item afterwards if they need to.

        Every item is either **FREE** (the shop window - anyone can see it, signed in
        or not) or **EXCLUSIVE** (behind the paywall). The creator chooses, per item.
        """)
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @Operation(
            summary = "Upload a photo",
            description = """
                    **Requires: APPROVED.**

                    How many you may have posted at once comes from your package.

                    **`tier` decides who sees it.** `FREE` puts it in the shop window - visible
                    to anyone, including anonymous visitors. `EXCLUSIVE` (the default) puts it
                    behind the paywall. Change it later with `PATCH /me/media/{id}`.

                    `tier` and `caption` bind either as query parameters or as multipart form
                    fields, so `?tier=FREE` and `-F tier=FREE` both work.

                    The first photo uploaded becomes the profile picture and is always `FREE`,
                    whatever you pass - a card with no image gives nobody a reason to pay.

                    **Published immediately.** Passing identity verification is what earns the
                    right to post, so there is no second review step. A moderator can take an
                    item down afterwards if it should not be up.

                    JPEG, PNG, WebP or HEIC.
                    """)
    @ApiResponse(responseCode = "201", description = "Photo published")
    @ApiResponse(responseCode = "403", description = "Identity not verified yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Photo limit reached",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/me/media/photos", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaResponse> uploadPhoto(
            @AuthenticationPrincipal AuthUser principal,
            @Parameter(description = "The image file", required = true) @RequestPart("file") MultipartFile file,
            @Parameter(description = "Optional caption") @RequestParam(required = false) String caption,
            @Parameter(description = "FREE or EXCLUSIVE. Defaults to EXCLUSIVE.")
            @RequestParam(required = false) ContentTier tier,
            @Parameter(description = "What a viewer pays for this one item. Null uses the platform default.")
            @RequestParam(required = false) Long priceMinor) {
        return ResponseEntity.status(201)
                .body(mediaService.upload(principal.user(), MediaType.PHOTO, file, caption, tier, priceMinor));
    }

    @Operation(
            summary = "Upload a video",
            description = """
                    **Requires: APPROVED.**

                    How many **premium** videos you may have posted at once comes from your package -
                    2 on Pro, 5 on Diamond, 10 on Black Diamond. Videos marked `FREE` are the
                    shop window and are not metered. MP4, QuickTime or WebM.

                    Same `tier` choice as photos: a `FREE` clip is a teaser anyone can watch,
                    `EXCLUSIVE` (the default) is what viewers pay for.

                    **Published immediately**, like photos.
                    """)
    @ApiResponse(responseCode = "201", description = "Video published")
    @ApiResponse(responseCode = "403", description = "Identity not verified yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "413", description = "File exceeds the size limit",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/me/media/videos", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaResponse> uploadVideo(
            @AuthenticationPrincipal AuthUser principal,
            @Parameter(description = "The video file", required = true) @RequestPart("file") MultipartFile file,
            @Parameter(description = "Optional caption") @RequestParam(required = false) String caption,
            @Parameter(description = "FREE or EXCLUSIVE. Defaults to EXCLUSIVE.")
            @RequestParam(required = false) ContentTier tier,
            @Parameter(description = "What a viewer pays for this one video. Null uses the platform default.")
            @RequestParam(required = false) Long priceMinor) {
        return ResponseEntity.status(201)
                .body(mediaService.upload(principal.user(), MediaType.VIDEO, file, caption, tier, priceMinor));
    }

    @Operation(summary = "List my media",
            description = "Includes anything a moderator has taken down, with the reason.")
    @ApiResponse(responseCode = "200", description = "The caller's media")
    @GetMapping("/me/media")
    public List<MediaResponse> listMine(@AuthenticationPrincipal AuthUser principal) {
        return mediaService.listOwn(principal.id());
    }

    @Operation(summary = "Edit a caption, reorder, move between free and exclusive, or set the profile picture")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "404", description = "Not found, or not owned by the caller",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PatchMapping("/me/media/{mediaId}")
    public MediaResponse update(@AuthenticationPrincipal AuthUser principal,
                                @PathVariable UUID mediaId,
                                @Valid @RequestBody MediaUpdateRequest request) {
        return mediaService.update(principal.user(), mediaId, request);
    }

    @Operation(summary = "Delete one of my media items")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Not found, or not owned by the caller",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @DeleteMapping("/me/media/{mediaId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser principal,
                                       @PathVariable UUID mediaId) {
        mediaService.delete(principal.user(), mediaId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "View a creator's gallery",
            description = """
                    **Public - no sign-in required.**

                    Moderator-approved media only, filtered by what the caller has paid for.
                    Anonymous callers are never entitled, so they always see the preview-only view.

                    Items past the free preview come back with `locked: true` and no `url`,
                    so a client can show blurred placeholders and a truthful count of what
                    unlocking would reveal. Sign in and unlock with
                    `POST /api/v1/billing/unlocks/{userId}`, or take out a subscription.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "That member's media, locked items included")
    @GetMapping("/members/{userId}/media")
    public List<MediaResponse> listForMember(@PathVariable UUID userId,
                                             @AuthenticationPrincipal AuthUser principal) {
        return mediaService.listPublic(userId, AuthUser.userOrNull(principal));
    }

    @Operation(summary = "Fetch the bytes of a media item",
            description = """
                    **Public for free-preview photos only** - so the preview URLs returned by
                    the gallery are not dead links.

                    Anything past the free preview returns `401` to an anonymous caller (sign
                    in) or `402` to a signed-in one who has not paid (unlock). Owners see their
                    own pending and rejected items; staff see everything.
                    """,
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "The file",
            content = @Content(mediaType = "application/octet-stream"))
    @ApiResponse(responseCode = "401", description = "Behind the paywall and the caller is anonymous - sign in",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "402", description = "Behind the paywall - unlock the creator first",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Not found or not visible to the caller",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/media/{mediaId}/file")
    public ResponseEntity<Resource> download(@PathVariable UUID mediaId,
                                             @AuthenticationPrincipal AuthUser principal) {
        var download = mediaService.download(mediaId, AuthUser.userOrNull(principal));
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        download.contentType() == null
                                ? org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE
                                : download.contentType()))
                // Play it, do not save it. Without this the browser is free to treat
                // the response as a file - and an octet-stream fallback in particular
                // pops a save dialog rather than rendering. There is no filename on
                // purpose: naming it invites a "Save as" with the name filled in.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                // Private, and never on a shared cache. A paid item must not survive
                // in a proxy where the next person to ask gets it without paying.
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .body(download.resource());
    }
}
