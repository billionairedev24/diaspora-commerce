package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.domain.rules.RuleBook;

/** Resolves the listing rules of a category. */
public interface ListingRulesService {

    /** Every rule. */
    RuleBook book();

    /**
     * The rules of a category code; throws not-found for a code the rules do not know. The code {@code general} answers
     * the general goods rules (what a requested or newly created category gets).
     */
    CategoryRules forCode(String categoryCode);

    /**
     * The rules of the category a product-type node belongs to (the node's parent category). A node without rules, a
     * category staff added after the matrix, gets the general goods rules; so does a pending category request (null).
     */
    CategoryRules forNode(CategoryNode node);
}
