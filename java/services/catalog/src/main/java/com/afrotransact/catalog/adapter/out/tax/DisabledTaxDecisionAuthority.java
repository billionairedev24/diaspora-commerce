package com.afrotransact.catalog.adapter.out.tax;

import java.util.Collection;
import java.util.Map;

import com.afrotransact.catalog.port.TaxDecisionAuthority;

/**
 * Explicitly switched off ({@code at.catalog.tax.authority=off}): for tests and stacks that run no tax-returns. It never
 * answers, so nothing is ever reported as resolved by it; callers see {@link #consulted()} false and rely on catalog's
 * own copy. Never configured in dev, uat or prod.
 */
public class DisabledTaxDecisionAuthority implements TaxDecisionAuthority {

    @Override
    public boolean consulted() {
        return false;
    }

    @Override
    public Map<String, Answer> ask(Collection<Ask> asks) {
        return Map.of();
    }
}
