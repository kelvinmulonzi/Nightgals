package com.nightgals.kyc;

import com.nightgals.common.ErrorResponse;
import com.nightgals.kyc.dto.KycSubmissionRequest;
import com.nightgals.kyc.dto.KycSubmissionResponse;
import com.nightgals.user.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "3. Identity verification (member)", description = """
        Submitting a government ID or passport. Nightgals only lets verified people
        take part, so every account passes through here before it can post anything.
        """)
@RestController
@RequestMapping("/api/v1/me/kyc")
@RequiredArgsConstructor
public class KycController {

    private final KycService kycService;

    @Operation(
            summary = "Start (or amend) a verification submission",
            description = """
                    Step 1 of 3.

                    Records the identity details printed on the document and opens a submission in
                    `DRAFT`. Calling it again while still in `DRAFT` amends those details without
                    losing already-uploaded images - unless the document type changes, which
                    discards them because a passport and a national ID need different photos.

                    Requirements:
                    - The caller must already have a profile (`PUT /api/v1/me/profile`)
                    - `dateOfBirth` must match the profile exactly
                    - The applicant must be 18 or over

                    The document number you send is hashed immediately and never stored in
                    readable form.
                    """)
    @ApiResponse(responseCode = "200", description = "Draft submission created or amended")
    @ApiResponse(responseCode = "400", description = "No profile yet, under 18, or date of birth mismatch",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Already verified, or a review is in progress",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public KycSubmissionResponse start(@AuthenticationPrincipal AuthUser principal,
                                       @Valid @RequestBody KycSubmissionRequest request) {
        return kycService.startOrUpdate(principal.user(), request);
    }

    @Operation(
            summary = "Upload one identity image",
            description = """
                    Step 2 of 3. Call once per required image.

                    | Document type | Required images |
                    |---|---|
                    | `NATIONAL_ID` | `ID_FRONT`, `ID_BACK`, `SELFIE` |
                    | `PASSPORT` | `PASSPORT_PAGE`, `SELFIE` |
                    | `DRIVERS_LICENSE` | `ID_FRONT`, `ID_BACK`, `SELFIE` |

                    Uploading a kind twice replaces the earlier file. The response's
                    `missingDocuments` tells you what is still outstanding, and `readyToSubmit`
                    turns true when nothing is.

                    JPEG, PNG, WebP or HEIC, up to the configured image size limit.
                    """)
    @ApiResponse(responseCode = "200", description = "Image stored")
    @ApiResponse(responseCode = "400", description = "Wrong image kind for this document type, or unsupported file",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "413", description = "File exceeds the size limit",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/documents/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KycSubmissionResponse uploadDocument(
            @AuthenticationPrincipal AuthUser principal,
            @Parameter(description = "Which image this is", example = "ID_FRONT")
            @PathVariable DocumentKind kind,
            @Parameter(description = "The image file", required = true)
            @RequestPart("file") MultipartFile file) {
        return kycService.uploadDocument(principal.user(), kind, file);
    }

    @Operation(
            summary = "Send the submission for review",
            description = """
                    Step 3 of 3. Moves the submission to `PENDING_REVIEW` and the account to
                    `verificationStatus = PENDING_REVIEW`. Every required image must be present.

                    From here a human reviewer decides. The applicant cannot edit the submission
                    while it is queued, but can withdraw it.
                    """)
    @ApiResponse(responseCode = "200", description = "Queued for review")
    @ApiResponse(responseCode = "400", description = "Required images are still missing",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/submit")
    public KycSubmissionResponse submit(@AuthenticationPrincipal AuthUser principal) {
        return kycService.submitForReview(principal.user());
    }

    @Operation(summary = "Get my current verification status",
            description = "Returns the most recent submission, whatever state it is in.")
    @ApiResponse(responseCode = "200", description = "The latest submission")
    @ApiResponse(responseCode = "404", description = "Nothing submitted yet",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public KycSubmissionResponse getCurrent(@AuthenticationPrincipal AuthUser principal) {
        return kycService.getCurrent(principal.id());
    }

    @Operation(summary = "List all my verification attempts, newest first")
    @ApiResponse(responseCode = "200", description = "Submission history")
    @GetMapping("/history")
    public List<KycSubmissionResponse> history(@AuthenticationPrincipal AuthUser principal) {
        return kycService.getHistory(principal.id());
    }

    @Operation(summary = "Withdraw a submission that is awaiting review",
            description = "Returns it to `DRAFT` so the details or images can be corrected.")
    @ApiResponse(responseCode = "200", description = "Returned to draft")
    @ApiResponse(responseCode = "404", description = "Nothing is awaiting review",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/withdraw")
    public KycSubmissionResponse withdraw(@AuthenticationPrincipal AuthUser principal) {
        return kycService.withdraw(principal.user());
    }
}
