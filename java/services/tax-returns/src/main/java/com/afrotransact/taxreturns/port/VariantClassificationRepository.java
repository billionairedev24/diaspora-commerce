package com.afrotransact.taxreturns.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.Evidence;
import com.afrotransact.taxreturns.domain.profile.ListingDecision;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;

/** Classification records per variant ATIN, their audit and evidence, and the stored decision per seller behaviour. */
public interface VariantClassificationRepository {

    Optional<VariantClassification> find(String atin);

    Map<String, VariantClassification> findAll(Collection<String> atins);

    VariantClassification insert(VariantClassification record);

    /** Updates with the optimistic-lock version of {@code record}; throws {@link StaleRecordException} when it moved. */
    VariantClassification update(VariantClassification record);

    Page list(Filter filter, int page, int size);

    void addAudit(String atin, AuditEntry entry);

    List<AuditEntry> audit(String atin);

    void addEvidence(String atin, Evidence evidence);

    List<Evidence> evidence(String atin);

    Optional<ListingDecision> decision(String atin, String behaviourHash);

    List<ListingDecision> decisions(String atin);

    void saveDecision(ListingDecision decision);

    /**
     * @param status       review state
     * @param categoryCode category
     * @param sellerOrgId  owner organisation or a seller seen in a stored decision
     * @param q            ATIN, GTIN or title
     */
    record Filter(RecordStatus status, String categoryCode, String sellerOrgId, String q) {
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
