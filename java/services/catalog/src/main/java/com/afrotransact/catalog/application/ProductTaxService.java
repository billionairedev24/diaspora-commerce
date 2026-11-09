package com.afrotransact.catalog.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.tax.TaxEvidence;
import com.afrotransact.catalog.domain.tax.TaxReadiness;

/** The tax side of products: the publish gate, evidence files, and what tax-returns tells catalog afterwards. */
public interface ProductTaxService {

    /**
     * The tax readiness of a product, per variant, with plain messages for what is still needed (owner or staff). Asks
     * tax-returns again for every variant and stores what it answers.
     */
    TaxReadiness readiness(UUID productId);

    /**
     * Stores the proof file of an exemption fact of a variant (owner or staff) as the next version, links it as the
     * fact's value and re-checks the variant.
     *
     * @throws com.afrotransact.catalog.domain.CatalogRuleViolation not an evidence fact, wrong type or too large
     */
    TaxEvidence uploadEvidence(String variantAtin, String factId, String fileName, String contentType, byte[] content);

    /** Every evidence file of a variant, newest version of each fact first (owner or staff). */
    List<TaxEvidence> evidence(String variantAtin);

    /** The file of an evidence record (owner or staff). */
    EvidenceFile evidenceFile(UUID evidenceId);

    record EvidenceFile(TaxEvidence meta, byte[] content) {
    }

    /**
     * tax-returns changed the classification of a variant (tax.classification.changed, kind VARIANT_CLASSIFICATION):
     * the status becomes the variant's tax status (APPROVED reads as READY). Unknown variants are ignored.
     */
    void classificationChanged(String variantAtin, String status);

    /** tax-returns changed the status of a category's tax profile (kind PROFILE_STATUS). */
    void profileStatusChanged(String categoryCode, String status);
}
