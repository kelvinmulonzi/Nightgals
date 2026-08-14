package com.nightgals.reels;

import com.nightgals.common.ErrorResponse;
import com.nightgals.reels.dto.ReelResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name = "11. My reels", description = """
        A creator's own promo clips.

        A reel is an advert for a profile: it shows on the public landing page, carries
        the poster's `creatorId`, and tapping it opens her profile. That is its entire
        job - it is the way in, not the thing being sold.

        **Free to watch** - a reel never sits behind a paywall, because its whole job is
        to pull a stranger towards a profile where the paid things are.

        **Posting one needs an active package**, like every other upload. A reel is prime
        placement on the landing page; a creator who has not paid to be on the platform
        should not be advertising on the front of it.

        Every reel deletes itself after 24 hours, file and all.
        """)
@RestController
@RequestMapping("/api/v1/me/reels")
@RequiredArgsConstructor
public class ReelCreatorController {

    private final ReelService reelService;

    @Operation(summary = "Post a reel",
            description = """
                    A short clip advertising your profile. Video only, same limits as any
                    other upload. It goes live at once.

                    **Requires: APPROVED, and an active package.** The verification bar is
                    the same as broadcasting - a reel is a public advert, and one from an
                    unverified account is the cheapest possible way to put anything on the
                    landing page. The package bar is the same as photos and video.

                    Capped per creator - three live at a time by default. The strip is a
                    shared shop window, and without a cap whoever uploads most simply
                    takes it, which reads as spam rather than as a directory.
                    """)
    @ApiResponse(responseCode = "201", description = "Posted, and showing now")
    @ApiResponse(responseCode = "400", description = "Not a video, or too large",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "402", description = "No active package",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Identity not verified",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409",
            description = "`reel_limit_reached` - you already have the maximum showing",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReelResponse> post(
            @AuthenticationPrincipal AuthUser principal,
            @Parameter(description = "The video file", required = true)
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Optional caption") @RequestParam(required = false) String caption) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reelService.post(principal.user(), file, caption));
    }

    @Operation(summary = "My reels",
            description = "Newest first, including any that have expired but not yet been swept away.")
    @ApiResponse(responseCode = "200", description = "This creator's reels")
    @GetMapping
    public List<ReelResponse> mine(@AuthenticationPrincipal AuthUser principal) {
        return reelService.mine(principal.id());
    }

    @Operation(summary = "Take one of mine down early",
            description = """
                    Removes the row and the file together. Somebody else's reel answers
                    404 rather than 403 - whether it exists is not the caller's business.
                    """)
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "404", description = "No such reel of yours",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @DeleteMapping("/{reelId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal AuthUser principal,
                                       @PathVariable UUID reelId) {
        reelService.remove(reelId, principal.user());
        return ResponseEntity.noContent().build();
    }
}
