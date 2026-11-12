package com.afrotransact.taxreturns.domain.profile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The classification record of one variant ATIN: its category, its facts (product facts from catalog plus the facts
 * staff set during review), the refined provider codes of a REFINE_CODE category and the review state. The tax
 * decision charged at checkout is this record's facts plus the seller's behaviour, resolved under the current rules.
 *
 * @param atin                 variant ATIN (the document's SKU)
 * @param gtin                 GTIN or null
 * @param productId            catalog product
 * @param title                product title (display)
 * @param ownerOrgId           the organisation that owns the product
 * @param categoryCode         tax profile (category) code
 * @param productTypeCode      catalog product type
 * @param facts                VARIANT facts by fact id, as catalog reported them
 * @param staffFacts           STAFF facts set during review (e.g. exemption_confirmed)
 * @param sellerBehaviour      behaviour facts of the owner's listing as catalog last reported them (the record-level
 *                             resolution uses them; each seller's own behaviour is resolved at quote time)
 * @param refinedProviderCodes verified SKU-level provider code per provider for REFINE_CODE categories
 * @param status               review state
 * @param resolution           the last resolution (for the queue and audit; the charge re-resolves)
 * @param ruleVersion          rule version of that resolution
 * @param classifiedBy         who submitted the facts
 * @param classifiedAt         when
 * @param reviewedBy           reviewer of the last approval or rejection
 * @param reviewedAt           when
 * @param decisionRef          reference of the tax professional's sign-off, when given
 * @param informationRequest   what review asked the seller for, null when nothing is pending
 * @param auditSampledAt       when the system approved it and put it in the audit sample, null when it is not an audit item
 * @param version              optimistic-lock version
 * @param createdAt            first seen
 * @param updatedAt            last change
 */
public record VariantClassification(String atin, String gtin, UUID productId, String title, String ownerOrgId,
                                    String categoryCode, String productTypeCode, Map<String, Object> facts,
                                    Map<String, Object> staffFacts, Map<String, Object> sellerBehaviour,
                                    Map<String, String> refinedProviderCodes,
                                    RecordStatus status, Resolution resolution, String ruleVersion,
                                    String classifiedBy, Instant classifiedAt, String reviewedBy, Instant reviewedAt,
                                    String decisionRef, InformationRequest informationRequest, Instant auditSampledAt,
                                    long version, Instant createdAt, Instant updatedAt) {

    /** Without the audit sample marker (not an audit item). */
    public VariantClassification(String atin, String gtin, UUID productId, String title, String ownerOrgId,
                                 String categoryCode, String productTypeCode, Map<String, Object> facts,
                                 Map<String, Object> staffFacts, Map<String, Object> sellerBehaviour,
                                 Map<String, String> refinedProviderCodes, RecordStatus status, Resolution resolution,
                                 String ruleVersion, String classifiedBy, Instant classifiedAt, String reviewedBy,
                                 Instant reviewedAt, String decisionRef, InformationRequest informationRequest,
                                 long version, Instant createdAt, Instant updatedAt) {
        this(atin, gtin, productId, title, ownerOrgId, categoryCode, productTypeCode, facts, staffFacts,
                sellerBehaviour, refinedProviderCodes, status, resolution, ruleVersion, classifiedBy, classifiedAt,
                reviewedBy, reviewedAt, decisionRef, informationRequest, null, version, createdAt, updatedAt);
    }

    public VariantClassification(String atin, String gtin, UUID productId, String title, String ownerOrgId,
                                 String categoryCode, String productTypeCode, Map<String, Object> facts,
                                 Map<String, Object> staffFacts, Map<String, Object> sellerBehaviour,
                                 Map<String, String> refinedProviderCodes, RecordStatus status, Resolution resolution,
                                 String ruleVersion, String classifiedBy, Instant classifiedAt, String reviewedBy,
                                 Instant reviewedAt, String decisionRef, long version, Instant createdAt,
                                 Instant updatedAt) {
        this(atin, gtin, productId, title, ownerOrgId, categoryCode, productTypeCode, facts, staffFacts,
                sellerBehaviour, refinedProviderCodes, status, resolution, ruleVersion, classifiedBy, classifiedAt,
                reviewedBy, reviewedAt, decisionRef, null, null, version, createdAt, updatedAt);
    }

    public VariantClassification {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        staffFacts = staffFacts == null ? Map.of() : Map.copyOf(staffFacts);
        sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        refinedProviderCodes = refinedProviderCodes == null ? Map.of() : Map.copyOf(refinedProviderCodes);
    }

    /** Product facts and staff facts together; staff answers win. */
    public Map<String, Object> allFacts() {
        Map<String, Object> all = new LinkedHashMap<>(facts);
        all.putAll(staffFacts);
        return all;
    }

    public boolean approved() {
        return status == RecordStatus.APPROVED;
    }

    public VariantClassification with(RecordStatus next, Resolution res, String rules, Instant at) {
        return new VariantClassification(atin, gtin, productId, title, ownerOrgId, categoryCode, productTypeCode, facts,
                staffFacts, sellerBehaviour, refinedProviderCodes, next, res, rules, classifiedBy, classifiedAt, reviewedBy, reviewedAt,
                decisionRef, informationRequest, next == RecordStatus.APPROVED ? auditSampledAt : null, version, createdAt, at);
    }

    public VariantClassification withFacts(String category, String type, Map<String, Object> newFacts,
                                           Map<String, Object> behaviour, String gtinValue, String titleValue, String by,
                                           Instant at) {
        boolean sameCategory = category.equals(categoryCode);
        return new VariantClassification(atin, gtinValue == null ? gtin : gtinValue, productId,
                titleValue == null ? title : titleValue, ownerOrgId, category, type, newFacts,
                sameCategory ? staffFacts : Map.of(), behaviour, sameCategory ? refinedProviderCodes : Map.of(), status, resolution, ruleVersion, by, at, reviewedBy, reviewedAt, decisionRef,
                null, auditSampledAt, version, createdAt, at);
    }

    /** The product title for display (staff queue); nothing else changes. */
    public VariantClassification withTitle(String newTitle) {
        return new VariantClassification(atin, gtin, productId, newTitle, ownerOrgId, categoryCode, productTypeCode,
                facts, staffFacts, sellerBehaviour, refinedProviderCodes, status, resolution, ruleVersion, classifiedBy,
                classifiedAt, reviewedBy, reviewedAt, decisionRef, informationRequest, auditSampledAt, version,
                createdAt, updatedAt);
    }

    public VariantClassification withReview(Map<String, Object> newStaffFacts, Map<String, String> refined,
                                            String by, Instant at, String ref) {
        return new VariantClassification(atin, gtin, productId, title, ownerOrgId, categoryCode, productTypeCode, facts,
                newStaffFacts, sellerBehaviour, refined, status, resolution, ruleVersion, classifiedBy, classifiedAt, by, at,
                ref == null ? decisionRef : ref, informationRequest, auditSampledAt, version, createdAt, at);
    }

    public VariantClassification withInformationRequest(InformationRequest request, Instant at) {
        return new VariantClassification(atin, gtin, productId, title, ownerOrgId, categoryCode, productTypeCode, facts,
                staffFacts, sellerBehaviour, refinedProviderCodes, status, resolution, ruleVersion, classifiedBy,
                classifiedAt, reviewedBy, reviewedAt, decisionRef, request, auditSampledAt, version, createdAt, at);
    }

    /** Approved by the system under the risk-based rule: who classified and reviewed it is {@code auto}. */
    public VariantClassification withAutoApproval(String by, Instant at, Instant sampledAt) {
        return new VariantClassification(atin, gtin, productId, title, ownerOrgId, categoryCode, productTypeCode, facts,
                staffFacts, sellerBehaviour, refinedProviderCodes, status, resolution, ruleVersion, by, at, by, at,
                decisionRef, informationRequest, sampledAt, version, createdAt, at);
    }

    /** A reviewer dealt with the audit item (confirmed it): it leaves the audit queue. */
    public VariantClassification withAuditClosed(String by, Instant at) {
        return new VariantClassification(atin, gtin, productId, title, ownerOrgId, categoryCode, productTypeCode, facts,
                staffFacts, sellerBehaviour, refinedProviderCodes, status, resolution, ruleVersion, classifiedBy,
                classifiedAt, by, at, decisionRef, informationRequest, null, version, createdAt, at);
    }

    /** In the audit sample: live, and waiting for a reviewer to confirm or reclassify. */
    public boolean auditItem() {
        return auditSampledAt != null;
    }
}
