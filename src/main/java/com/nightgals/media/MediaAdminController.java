package com.nightgals.media;

import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import com.nightgals.media.dto.MediaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "6. Media moderation (admin)", description = """
        Removing content after the fact. `MODERATOR` and `ADMIN` only.

        **There is no review queue.** Identity verification is the gate: once a
        creator has passed KYC their uploads publish immediately. Nothing waits on
        staff, and nothing here has to be worked through daily.

        What is left is takedown - the remedy when a creator posts something that
        should not be up. The creator sees the reason on their own media listing.
        """)
@RestController
@RequestMapping("/api/v1/admin/media")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
public class MediaAdminController {

    private final MediaService mediaService;

    @Operation(summary = "Recently posted media",
            description = "Newest first, across all creators. For spot-checking what is going up.")
    @ApiResponse(responseCode = "200", description = "Recent media")
    @GetMapping("/recent")
    public PageResponse<MediaResponse> recent(@PageableDefault(size = 20) Pageable pageable) {
        return mediaService.recentMedia(pageable);
    }

    @Operation(summary = "How much media has been taken down")
    @ApiResponse(responseCode = "200", description = "Taken-down count")
    @GetMapping("/taken-down/count")
    public Map<String, Long> takenDownCount() {
        return Map.of("takenDown", mediaService.takenDownCount());
    }

    @Operation(summary = "Take a media item down",
            description = """
                    Hides it from everyone but its owner and staff. The reason is required and
                    is shown to the creator, so a removal is never silent.
                    """)
    @ApiResponse(responseCode = "200", description = "Taken down")
    @ApiResponse(responseCode = "400", description = "No reason given",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such media",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{mediaId}/takedown")
    public MediaResponse takeDown(@PathVariable UUID mediaId,
                                  @Parameter(description = "Shown to the creator", required = true)
                                  @RequestParam String reason) {
        return mediaService.takeDown(mediaId, reason);
    }

    @Operation(summary = "Restore a taken-down item")
    @ApiResponse(responseCode = "200", description = "Visible again")
    @PostMapping("/{mediaId}/restore")
    public MediaResponse restore(@PathVariable UUID mediaId) {
        return mediaService.restore(mediaId);
    }

    @Operation(
            summary = "Everything one creator has posted",
            description = """
                    Her whole gallery as staff see it — taken-down items included, with the
                    reason each was removed. This is the list the moderation view works
                    from, so it must show what is hidden as well as what is live.
                    """)
    @ApiResponse(responseCode = "200", description = "Her media, newest ordering first")
    @GetMapping("/by-user/{userId}")
    public java.util.List<MediaResponse> byUser(@PathVariable UUID userId) {
        return mediaService.listOwn(userId);
    }

    @Operation(
            summary = "Delete an item permanently",
            description = """
                    Removes the row **and the file**. This cannot be undone — prefer a takedown,
                    which hides the item and can be reversed.

                    A reason is required and the creator is emailed it. Content that vanishes
                    with no message is indistinguishable from a bug, and somebody who is not
                    told why cannot avoid the same removal tomorrow.
                    """)
    @ApiResponse(responseCode = "204", description = "Gone")
    @ApiResponse(responseCode = "400", description = "No reason given",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> deletePermanently(
            @PathVariable UUID mediaId,
            @Parameter(description = "Emailed to the creator", required = true)
            @RequestParam String reason) {
        mediaService.deleteAsAdmin(mediaId, reason);
        return ResponseEntity.noContent().build();
    }
}
