package com.afrotransact.taxreturns.port;

import java.time.Instant;
import java.util.List;

import com.afrotransact.taxreturns.domain.shadow.ShadowRow;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/** Shadow comparisons of provider quotes with the in-house quote. */
public interface ShadowQuoteRepository {

    void saveAll(List<ShadowRow> rows);

    Totals totals(Instant from, Instant to, TaxProviderId provider);

    List<Group> groups(Dimension dimension, Instant from, Instant to, TaxProviderId provider);

    List<ShadowRow> rows(Instant from, Instant to, TaxProviderId provider, boolean onlyDifferences, int page, int size);

    /** What the report groups by. */
    enum Dimension {
        INTERNAL_TAX_CODE, CATEGORY, OUTCOME, STATE, KIND
    }

    record Totals(long compared, long differing, long absoluteDifferenceMinor, long netDifferenceMinor) {
    }

    /**
     * @param key          the group (an internal code, a category, an outcome, a state or a kind)
     * @param providerCode the provider code, only for INTERNAL_TAX_CODE groups
     */
    record Group(String key, String providerCode, long lines, long differing, long absoluteDifferenceMinor) {
    }
}
