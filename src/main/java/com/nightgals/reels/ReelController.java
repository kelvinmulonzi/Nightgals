package com.nightgals.reels;

import com.nightgals.common.ErrorResponse;
import com.nightgals.reels.dto.ReelResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "9. Reels", description = """
        Creators' short promo clips on the public site. **Reading is public - no
        sign-in required.**

        A reel advertises the creator who posted it: every entry carries `creatorId`,
        and a client should open her profile when one is tapped. That is the whole
        purpose of the strip - it is a way into a profile, not a video feed.

        Free to watch and free to post. Posting is under `/me/reels`.

        Each one is live for 24 hours and then deletes itself, files and all. Expired
        reels are gone from the listing immediately and return 404 on their file, even
        in the window before the purge sweep removes the row.
        """)
@RestController
@RequestMapping("/api/v1/reels")
@RequiredArgsConstructor
public class ReelController {

    private final ReelService reelService;
    private final com.nightgals.views.ViewCounterService viewCounter;

    @Operation(summary = "The reels showing right now",
            description = "Newest first. Empty when nothing is live.",
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "Live reels")
    @GetMapping
    public List<ReelResponse> live() {
        return reelService.live();
    }

    @Operation(summary = "Fetch a reel's video",
            security = @SecurityRequirement(name = ""))
    @ApiResponse(responseCode = "200", description = "The file")
    @ApiResponse(responseCode = "404", description = "No such reel, or it has expired",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{reelId}/file")
    public ResponseEntity<Resource> file(
            @PathVariable UUID reelId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.nightgals.user.AuthUser principal,
            jakarta.servlet.http.HttpServletRequest request) {
        var download = reelService.download(reelId);
        // Counted on the file, not on the strip. Every reel on the landing page
        // requests its own bytes, so this is the moment somebody actually watched
        // one rather than the moment the page listed it.
        viewCounter.record(com.nightgals.views.ViewSubject.REEL, reelId,
                com.nightgals.user.AuthUser.userOrNull(principal), download.ownerId(), request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        download.contentType() == null
                                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                : download.contentType()))
                // Play it, do not save it - the same treatment paid media gets.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                // Public, but never cached beyond the reel's own life: a proxy
                // holding one for a day would outlive the reel itself.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(download.resource());
    }
}
