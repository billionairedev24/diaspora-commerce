package com.afrotransact.seller.domain;

import java.time.LocalDate;

/**
 * Step "business details", which depends on the entity type. The full SSN/ITIN/EIN is never stored in clear: only
 * {@code taxIdLast4} and {@code taxIdCipher} (encrypted by the {@code FieldCipher} port).
 */
public record BusinessDetailsSection(String legalFirstName, String legalLastName, String legalName, String dba,
                                     TaxIdType taxIdType, String taxIdLast4, String taxIdCipher,
                                     LocalDate dateOfBirth, String stateOfFormation, LocalDate dateOfFormation,
                                     Address address, String formationDocumentAssetId,
                                     String irsDeterminationLetterAssetId) {

    public BusinessDetailsSection withTaxId(TaxIdType type, String last4, String cipher) {
        return new BusinessDetailsSection(legalFirstName, legalLastName, legalName, dba, type, last4, cipher,
                dateOfBirth, stateOfFormation, dateOfFormation, address, formationDocumentAssetId,
                irsDeterminationLetterAssetId);
    }

    public boolean hasTaxId() {
        return taxIdCipher != null && taxIdLast4 != null;
    }
}
