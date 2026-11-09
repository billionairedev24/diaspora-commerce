package com.afrotransact.taxreturns.application;

import java.time.LocalDate;
import java.util.Map;

import com.afrotransact.taxreturns.domain.profile.Resolution;

/** Resolves a category's tax outcome from facts without charging anything (the publish gate, forms, previews). */
public interface ClassificationResolveService {

    /**
     * Pure rules evaluation (tax:classify, tax:read or tax:classify-admin). Never guesses; idempotent and cached by rule
     * version. 404 tax.profile-not-found for an unknown category.
     */
    Resolution resolve(ResolveCommand command);

    /**
     * @param categoryCode    category code
     * @param productTypeCode product type (accepted, not used by the rules)
     * @param facts           product facts by fact id
     * @param sellerBehaviour seller behaviour facts by fact id
     * @param context         system facts
     * @param shipToState     two-letter state, null = the launch state
     * @param saleDate        null = today
     */
    record ResolveCommand(String categoryCode, String productTypeCode, Map<String, Object> facts,
                          Map<String, Object> sellerBehaviour, Map<String, Object> context, String shipToState,
                          LocalDate saleDate) {

        public ResolveCommand {
            facts = facts == null ? Map.of() : facts;
            sellerBehaviour = sellerBehaviour == null ? Map.of() : sellerBehaviour;
            context = context == null ? Map.of() : context;
        }
    }
}
