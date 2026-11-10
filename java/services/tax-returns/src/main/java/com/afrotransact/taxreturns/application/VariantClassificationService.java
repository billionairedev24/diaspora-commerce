package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.Evidence;
import com.afrotransact.taxreturns.domain.profile.ListingDecision;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;

/**
 * Classification records per variant ATIN: created and refreshed when catalog reports a product's tax facts, reviewed by
 * staff (the review queue is status PENDING_REVIEW), approved or reclassified with a reason, always audited.
 */
public interface VariantClassificationService {

    /**
     * Creates or refreshes the records of a product's variants (catalog.product.tax-facts-changed, or the first quote
     * of a variant without a record). Idempotent: an event that changes nothing changes nothing. System call, no actor.
     */
    void onCatalogFactsChanged(CatalogFactsChanged event);

    /** Records, newest change first within the filter (tax:read). */
    VariantClassificationRepository.Page list(RecordStatus status, String categoryCode, String sellerOrgId, String q,
                                              int page, int size);

    /** One record with its audit, evidence and stored decisions (tax:read); 404 tax.classification-not-found. */
    ClassificationView get(String atin);

    /**
     * Approves after review (tax:classify-admin). 422 tax.classification-not-resolvable unless the profile resolves to a
     * chargeable decision; staff facts and refined provider codes are recorded here.
     */
    ClassificationView approve(String atin, ApproveCommand command);

    /** Moves the variant to another category, with a reason (tax:classify-admin); the record returns to review. */
    ClassificationView reclassify(String atin, ReclassifyCommand command);

    /**
     * Asks the seller for more information before a decision (tax:classify-admin): the message (10 to 1000 characters) is
     * stored on the record with an audit row and published in tax.classification.changed (reason = the message) so catalog
     * and notifications can show or send it; it is cleared when the seller's facts change.
     */
    ClassificationView requestInformation(String atin, String message);

    /**
     * @param productId       catalog product
     * @param ownerOrgId      the product's owner organisation
     * @param categoryCode    tax profile (category) code
     * @param productTypeCode product type
     * @param sellerBehaviour the owner listing's behaviour facts
     * @param variants        the variants with their current facts
     * @param changedBy       who changed it
     * @param occurredAt      when
     */
    record CatalogFactsChanged(UUID productId, String ownerOrgId, String categoryCode, String productTypeCode,
                               Map<String, Object> sellerBehaviour, List<Variant> variants, String changedBy,
                               Instant occurredAt) {

        public CatalogFactsChanged {
            sellerBehaviour = sellerBehaviour == null ? Map.of() : sellerBehaviour;
            variants = variants == null ? List.of() : List.copyOf(variants);
        }
    }

    /**
     * @param atin            variant ATIN
     * @param gtin            GTIN or null
     * @param facts           all current VARIANT facts by fact id
     * @param evidenceChanged an evidence file was added or replaced
     */
    record Variant(String atin, String gtin, Map<String, Object> facts, boolean evidenceChanged) {

        public Variant {
            facts = facts == null ? Map.of() : facts;
        }
    }

    /**
     * @param reason               why approved (optional)
     * @param staffFacts           facts owned by STAFF, e.g. exemption_confirmed
     * @param refinedProviderCodes verified SKU-level code per provider (REFINE_CODE categories)
     * @param decisionRef          reference of the tax professional's sign-off
     */
    record ApproveCommand(String reason, Map<String, Object> staffFacts, Map<String, String> refinedProviderCodes,
                          String decisionRef) {

        public ApproveCommand {
            staffFacts = staffFacts == null ? Map.of() : staffFacts;
            refinedProviderCodes = refinedProviderCodes == null ? Map.of() : refinedProviderCodes;
        }
    }

    /**
     * @param categoryCode    the new category
     * @param productTypeCode the new product type, optional
     * @param reason          why (at least 3 characters)
     */
    record ReclassifyCommand(String categoryCode, String productTypeCode, String reason) {
    }

    /**
     * @param record     the record
     * @param resolution the record resolved under today's rules and the owner's behaviour; null for an unknown category
     * @param evidence   evidence references
     * @param audit      the audit trail, oldest first
     * @param decisions  stored decision per seller behaviour
     * @param ordersAffected orders placed here that contain the variant
     * @param ordersAffected orders placed with this variant
     */
    record ClassificationView(VariantClassification record, Resolution resolution, List<Evidence> evidence,
                              List<AuditEntry> audit, List<ListingDecision> decisions, long ordersAffected) {
    }
}
