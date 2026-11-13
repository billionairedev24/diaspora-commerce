package com.afrotransact.catalog.application;

import java.util.List;

import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.StoredRow;

/**
 * The rows of a CREATE upload whose barcode matched a page that already exists (S393): they are applied as offers on that
 * page, and the children of a mixed parent group that are not on the page yet are filed as NEW_VARIANT requests. Called by
 * the product import job for the groups whose key starts with {@link #OFFER_GROUP} or {@link #VARIANT_GROUP}. Never
 * creates a page and never sets a status.
 */
public interface MatchedRowsJob {

    /** Group key prefix of rows that became offers: {@code offer:<row>}. */
    String OFFER_GROUP = "offer:";
    /** Group key prefix of rows filed as NEW_VARIANT requests: {@code variant:<row>}. */
    String VARIANT_GROUP = "variant:";
    /** Group key prefix of rows that are the same as when they were imported: skipped, not duplicates. */
    String SKIP_GROUP = "skip:";

    /** Whether the product import job hands this group to {@link #apply}. */
    static boolean handles(String groupKey) {
        return groupKey != null && (groupKey.startsWith(OFFER_GROUP) || groupKey.startsWith(VARIANT_GROUP)
                || groupKey.startsWith(SKIP_GROUP));
    }

    /** Applies the pending rows of one such group and records their outcomes. */
    void apply(ProductImport upload, String groupKey, List<StoredRow> rows);
}
