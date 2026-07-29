package com.nightgals.kyc;

import com.nightgals.common.ErrorResponse;
import com.nightgals.common.PageResponse;
import com.nightgals.kyc.dto.KycReviewItemResponse;
import com.nightgals.kyc.dto.KycReviewRequest;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "4. Identity verification (admin)", description = """
        The review desk. Restricted to `MODERATOR` and `ADMIN`.

        Reviewers compare the applicant's stated name and date of birth against the
        uploaded document, and the selfie against the photo on that document. Every
        document view is written to an audit log.
        """)
@RestController
@RequestMapping("/api/v1/admin/kyc")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
public class KycAdminController {

    private final KycReviewService reviewService;

    @Operation(
            summary = "The review queue",
            description = "Submissions awaiting a decision, oldest first.")
    @ApiResponse(responseCode = "200", description = "Pending submissions")
    @ApiResponse(responseCode = "403", description = "Caller is not staff",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/queue")
    public PageResponse<KycReviewItemResponse> queue(
            @Parameter(description = "Zero-based page index") @PageableDefault(size = 20) Pageable pageable) {
        return reviewService.queue(pageable);
    }

    @Operation(summary = "How many submissions are waiting",
            description = "Cheap enough to poll for a dashboard badge.")
    @ApiResponse(responseCode = "200", description = "Pending count")
    @GetMapping("/queue/count")
    public Map<String, Long> pendingCount() {
        return Map.of("pending", reviewService.pendingCount());
    }

    @Operation(summary = "Every submission, newest first",
            description = "Includes decided ones, for auditing past decisions.")
    @ApiResponse(responseCode = "200", description = "All submissions")
    @GetMapping
    public PageResponse<KycReviewItemResponse> all(@PageableDefault(size = 20) Pageable pageable) {
        return reviewService.all(pageable);
    }

    @Operation(summary = "One submission in full")
    @ApiResponse(responseCode = "200", description = "Submission detail")
    @ApiResponse(responseCode = "404", description = "No such submission",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{submissionId}")
    public KycReviewItemResponse get(@PathVariable UUID submissionId) {
        return reviewService.get(submissionId);
    }

    @Operation(
            summary = "Open an identity document image",
            description = """
                    Streams the raw image so the reviewer can read it.

                    **Every call writes an audit row** recording which staff member opened which
                    document, when, and from which IP. Files are served inline and are never
                    reachable without an authenticated staff session - there is no public URL.

                    Returns 404 once the file has been purged under the retention policy.
                    """)
    @ApiResponse(responseCode = "200", description = "The image bytes",
            content = @Content(mediaType = "application/octet-stream"))
    @ApiResponse(responseCode = "404", description = "Unknown document, or already purged",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/documents/{documentId}/file")
    public ResponseEntity<Resource> downloadDocument(@PathVariable UUID documentId,
                                                     @AuthenticationPrincipal AuthUser principal,
                                                     HttpServletRequest request) {
        var download = reviewService.downloadDocument(documentId, principal.user(), clientIp(request));

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        download.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                : download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + download.kind() + "\"")
                // Identity documents must not be written to any shared cache.
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .body(download.resource());
    }

    @Operation(
            summary = "Approve or reject a submission",
            description = """
                    Records the decision and moves the member's account with it.

                    - **Approve** sets `verificationStatus = APPROVED`, which unlocks photo and
                      video upload for that member.
                    - **Reject** sets `REJECTED` and requires a `rejectionReason`, which the
                      applicant sees. `reviewerNotes` stays internal.

                    A reviewer cannot decide their own submission, and a submission that has
                    already been decided cannot be decided again.
                    """)
    @ApiResponse(responseCode = "200", description = "Decision recorded")
    @ApiResponse(responseCode = "400", description = "Rejection without a reason",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Not pending, or the document verifies another account",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{submissionId}/review")
    public KycReviewItemResponse review(@PathVariable UUID submissionId,
                                        @Valid @RequestBody KycReviewRequest request,
                                        @AuthenticationPrincipal AuthUser principal) {
        return reviewService.review(submissionId, request, principal.user());
    }

    /** Prefers the proxy header, since the app sits behind one in every real deployment. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
