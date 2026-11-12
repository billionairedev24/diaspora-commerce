package com.afrotransact.catalog.domain.imports;

import java.util.Map;

/**
 * What offer row validation needs: the pages the file's rows name (by {@link TargetKey#id()}) and the row cap.
 *
 * @param targets the match result for every key in the file
 * @param maxRows rows per file (5,000 by default, a setting)
 */
public record OfferImportContext(Map<String, OfferTarget> targets, int maxRows) {

    public OfferImportContext {
        targets = Map.copyOf(targets);
    }
}
