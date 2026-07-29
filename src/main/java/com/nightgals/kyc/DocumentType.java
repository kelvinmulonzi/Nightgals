package com.nightgals.kyc;

import java.util.Set;

public enum DocumentType {
    NATIONAL_ID(Set.of(DocumentKind.ID_FRONT, DocumentKind.ID_BACK, DocumentKind.SELFIE)),
    PASSPORT(Set.of(DocumentKind.PASSPORT_PAGE, DocumentKind.SELFIE)),
    DRIVERS_LICENSE(Set.of(DocumentKind.ID_FRONT, DocumentKind.ID_BACK, DocumentKind.SELFIE));

    private final Set<DocumentKind> requiredKinds;

    DocumentType(Set<DocumentKind> requiredKinds) {
        this.requiredKinds = requiredKinds;
    }

    /** Which images must be present before the submission can be sent for review. */
    public Set<DocumentKind> requiredKinds() {
        return requiredKinds;
    }
}
