package com.nightgals.kyc.dto;

import com.nightgals.kyc.RejectionReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "An administrator's decision on a verification attempt")
public record KycReviewRequest(

        @Schema(description = "true approves the member, false rejects them",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Boolean approve,

        @Schema(description = "Required when approve = false. Shown to the applicant.")
        RejectionReason rejectionReason,

        @Schema(description = "Internal note. Never shown to the applicant.", maxLength = 1000)
        @Size(max = 1000) String reviewerNotes) {
}
