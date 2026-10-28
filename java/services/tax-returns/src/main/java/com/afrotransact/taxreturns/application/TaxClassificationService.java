package com.afrotransact.taxreturns.application;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.domain.tax.ClassificationRule;
import com.afrotransact.taxreturns.domain.tax.TaxClassification;
import com.afrotransact.taxreturns.domain.tax.TaxCode;
import com.afrotransact.taxreturns.port.TaxClassificationRepository;

/** Staff tools for tax classification: codes, rules, the review queue and tax by code. */
public interface TaxClassificationService {

    List<TaxCode> codes();

    TaxCode saveCode(TaxCode code);

    List<ClassificationRule> rules();

    ClassificationRule saveRule(ClassificationRule rule);

    void deleteRule(long id);

    /** What a product type with these answers would be classified as (nothing is recorded). */
    TaxClassification test(String productType, Map<String, String> attributes);

    /** Items classified by default (the safe general rate) that no one has reviewed yet. */
    List<TaxClassificationRepository.Seen> reviewQueue(boolean includeReviewed);

    void markReviewed(String productType, String attrHash, String note);

    /** Sales minus refunds per tax code and jurisdiction for a month. */
    List<TaxClassificationRepository.CodeRow> liabilityByCode(YearMonth month);
}
