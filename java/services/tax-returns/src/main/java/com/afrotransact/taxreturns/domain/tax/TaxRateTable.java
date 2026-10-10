package com.afrotransact.taxreturns.domain.tax;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Snapshot of the configured state rules and local rates, with ZIP lookup (exact ZIP first, then 3-digit prefix). */
public final class TaxRateTable {

    private final Map<String, StateTaxRule> states = new HashMap<>();
    private final Map<String, LocalTaxRate> locals = new HashMap<>();

    public TaxRateTable(List<StateTaxRule> stateRules, List<LocalTaxRate> localRates) {
        for (StateTaxRule r : stateRules) {
            states.put(r.country() + "-" + r.state(), r);
        }
        for (LocalTaxRate l : localRates) {
            locals.put(l.state() + ":" + l.zipPrefix(), l);
        }
    }

    public Optional<StateTaxRule> state(TaxAddress to) {
        return Optional.ofNullable(states.get(to.country() + "-" + to.state()));
    }

    /** The local rate for the address: an exact 5-digit ZIP entry wins over a 3-digit prefix entry. */
    public Optional<LocalTaxRate> local(TaxAddress to) {
        String zip = to.zip5();
        if (zip.isEmpty()) {
            return Optional.empty();
        }
        LocalTaxRate exact = locals.get(to.state() + ":" + zip);
        return exact != null ? Optional.of(exact) : Optional.ofNullable(locals.get(to.state() + ":" + zip.substring(0, 3)));
    }
}
