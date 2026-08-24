package com.nightgals.kyc.dto;

import com.nightgals.kyc.DocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(description = "Identity details, exactly as printed on the document")
public record KycSubmissionRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull DocumentType documentType,

        @Schema(description = "Full legal name as it appears on the document",
                example = "Amina Wanjiru Kamau", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 2, max = 150)
        String fullName,

        @Schema(description = "Date of birth on the document. Must match the profile.",
                example = "1998-04-12", requiredMode = Schema.RequiredMode.REQUIRED)
        @Past LocalDate dateOfBirth,

        @Schema(description = "ISO 3166-1 alpha-2 country that issued the document",
                example = "KE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "Must be a 2-letter country code")
        String countryOfIssue,

        @Schema(description = """
                The document number. It is hashed on arrival and never stored in
                readable form - only the hash and the last 4 characters are kept.
                """, example = "A012345678", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 4, max = 40)
        String documentNumber) {
}
