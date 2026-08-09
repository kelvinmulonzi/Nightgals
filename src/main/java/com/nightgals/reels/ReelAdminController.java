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

@Tag(name = "10. Staff · Reels", description = """
        Posting the short clips that run on the public site. **Requires: MODERATOR or ADMIN.**

        A reel is the platform's own promotional content - it is free to everyone,
        belongs to no creator, counts against no package, and deletes itself 24 hours
        after it is posted.
        """)
@RestController
@RequestMapping("/api/v1/admin/reels")
@RequiredArgsConstructor
public class ReelAdminController {

    private final ReelService reelService;

    @Operation(summary = "Everything posted, expired ones included",
            description = "The public listing hides expired reels; this shows them until the sweep clears them.")
    @ApiResponse(responseCode = "200", description = "All reels, newest first")
    @GetMapping
    public List<ReelResponse> all() {
        return reelService.all();
    }

    @Operation(summary = "Post a reel",
            description = "Video only, same limits as creator uploads. It goes live at once and expires in 24 hours.")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "400", description = "Not a video, or too large",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReelResponse> post(
            @AuthenticationPrincipal AuthUser principal,
            @Parameter(description = "The video file", required = true) @RequestPart("file") MultipartFile file,
            @Parameter(description = "Optional caption") @RequestParam(required = false) String caption) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reelService.post(principal.user(), file, caption));
    }

    @Operation(summary = "Take a reel down early")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(responseCode = "404", description = "No such reel",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @DeleteMapping("/{reelId}")
    public ResponseEntity<Void> remove(@PathVariable UUID reelId) {
        reelService.remove(reelId);
        return ResponseEntity.noContent().build();
    }
}
