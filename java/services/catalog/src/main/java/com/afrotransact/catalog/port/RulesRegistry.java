package com.afrotransact.catalog.port;

import com.afrotransact.catalog.domain.rules.RuleBook;

/** The listing rules: the one source of what each kind of product and each category asks of a listing. */
public interface RulesRegistry {

    /** Every kind, field and category rule. */
    RuleBook book();
}
