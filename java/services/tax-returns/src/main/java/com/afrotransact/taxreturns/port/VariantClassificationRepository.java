package com.afrotransact.taxreturns.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.Evidence;
import com.afrotransact.taxreturns.domain.profile.ListingDecision;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.ReviewReason;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;

/** Classification records per variant ATIN, their audit and evidence, and the stored decision per seller behaviour. */
public interface VariantClassificationRepository {

    Optional<VariantClassification> find(String atin);

    Map<String, VariantClassification> findAll(Collection<String> atins);

    VariantClassification insert(VariantClassification record);

    /** Updates with the optimistic-lock version of {@code record}; throws {@link StaleRecordException} when it moved. */
    VariantClassification update(VariantClassification record);

    Page list(Filter filter, int page, int size);

    /** ATINs of every record in this status, oldest change first. */
    List<String> atinsWithStatus(RecordStatus status);

    void addAudit(String atin, AuditEntry entry);

    List<AuditEntry> audit(String atin);

    void addEvidence(String atin, Evidence evidence);

    List<Evidence> evidence(String atin);

    Optional<ListingDecision> decision(String atin, String behaviourHash);

    List<ListingDecision> decisions(String atin);

    void saveDecision(ListingDecision decision);

    /** Orders (placed here) that contain the variant: what a reclassification would not touch but reviewers want to see. */
    long ordersAffected(String atin);

    /** The organisations that sell each variant: its owner and every seller seen in a stored decision. */
    Map<String, List<String>> sellerOrgIds(Collection<String> atins);

    /**
     * @param status       review state
     * @param categoryCode category
     * @param sellerOrgId  owner organisation or a seller seen in a stored decision
     * @param q            ATIN, GTIN or title; also matches a record sold by one of {@code qSellerOrgIds}
     * @param auditOnly    only the audit sample: approved by the system and waiting for a reviewer to confirm
     * @param reason       only records that need a person for this reason (see {@link ReviewReason})
     * @param qSellerOrgIds organisations whose name matched the search text (the name lives in another service)
     * @param sort         {@code title}, {@code atin}, {@code category}, {@code status}, {@code submitted} or
     *                     {@code reviewed}; null keeps the queue order
     * @param descending   direction of {@code sort}
     */
    record Filter(RecordStatus status, String categoryCode, String sellerOrgId, String q, boolean auditOnly,
                  ReviewReason reason, List<String> qSellerOrgIds, String sort, boolean descending) {

        public Filter {
            qSellerOrgIds = qSellerOrgIds == null ? List.of() : List.copyOf(qSellerOrgIds);
        }

        public Filter(RecordStatus status, String categoryCode, String sellerOrgId, String q, boolean auditOnly) {
            this(status, categoryCode, sellerOrgId, q, auditOnly, null, List.of(), null, false);
        }

        public Filter(RecordStatus status, String categoryCode, String sellerOrgId, String q) {
            this(status, categoryCode, sellerOrgId, q, false);
        }
    }

    record Page(List<VariantClassification> items, long total) {
    }

    /** Another writer changed the record first. */
    class StaleRecordException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public StaleRecordException(String atin) {
            super("The classification of " + atin + " changed; reload it");
        }
    }
}
