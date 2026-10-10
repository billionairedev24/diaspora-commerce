package com.afrotransact.seller.domain;

import java.time.LocalDate;

/** What the applicant typed on "business details"; {@code taxId} is the raw SSN/ITIN/EIN (null = unchanged). */
public record BusinessDetailsInput(String legalFirstName, String legalLastName, String legalName, String dba,
                                   String taxId, LocalDate dateOfBirth, String stateOfFormation,
                                   LocalDate dateOfFormation, Address address, String formationDocumentAssetId,
                                   String irsDeterminationLetterAssetId) {
}
