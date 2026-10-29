package com.afrotransact.taxreturns.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.tax.ClassificationRule;
import com.afrotransact.taxreturns.domain.tax.TaxClassification;
import com.afrotransact.taxreturns.domain.tax.TaxCode;

/** Tax codes, the rules that assign them, the items seen for review, and the code each placed order line was taxed under. */
public interface TaxClassificationRepository {

    List<TaxCode> codes();

    TaxCode upsertCode(TaxCode code);

    List<ClassificationRule> rules();

    ClassificationRule upsertRule(ClassificationRule rule);

    boolean deleteRule(long id);

    /** Notes an item that needs a person (or counts one more sighting), keyed by product type and attribute answers. */
    void recordSeen(String productType, Map<String, String> attributes, TaxClassification classification,
                    String sampleAtin);

    List<Seen> reviewQueue(boolean includeReviewed, int limit);

    /** Marks every sighting of this type and answers as reviewed; false when there is none. */
    boolean markReviewed(String productType, String attrHash, String reviewer, String note);

    /** Stores the code a placed order line was taxed under, for the by-code report. */
    void tagLine(String lineId, String taxCode);

    /** Tax by code and jurisdiction for a month (sales minus refunds), from the ledger. */
    List<CodeRow> liabilityByCode(Instant from, Instant to);

    /**
     * One item in the review queue.
     *
     * @param attrHash hash identifying the attribute answers (use it to mark reviewed)
     */
    record Seen(String productType, Map<String, String> attributes, String attrHash, String taxCode, String basis,
                String sampleAtin, long count, Instant firstSeen, Instant lastSeen, Instant reviewedAt,
                String reviewedBy, String reviewNote) {
    }

    /** Tax of one code in one jurisdiction. */
    record CodeRow(String taxCode, String jurisdiction, String currency, long taxableMinor, long taxMinor) {
    }

    default Optional<TaxCode> code(String code) {
        return codes().stream().filter(c -> c.code().equals(code)).findFirst();
    }
}
