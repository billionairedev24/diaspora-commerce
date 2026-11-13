package com.afrotransact.catalog.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.port.ProductImportRepository.RowResult;

/**
 * Applies stored upload rows as offers (S393, S394): used by the offer upload job and by CREATE uploads whose rows matched an
 * existing page. Matches again at run time (a page may have changed), holds what is not cleared, sends the rest to
 * offers-service in one batch and says in plain words what happened to each row. Never creates a page and never sets a status.
 */
public interface OfferRowProcessor {

    /**
     * Offer rows of an OFFER upload, or the rows of a CREATE upload that became offers: one result per row, in the order
     * given.
     *
     * @throws com.afrotransact.catalog.port.OfferWriter.OffersUnavailable when offers-service did not answer; nothing may be
     *                                                                     recorded then
     */
    List<RowResult> offers(ProductImport upload, List<StoredRow> rows);

    /** Rows of a price and quantity feed. */
    List<RowResult> feed(ProductImport upload, List<StoredRow> rows);

    /**
     * Saves results for rows that are still pending, with the audit rows and the upload's counters, in one transaction that
     * holds the upload's lock. Rows another run finished first are left alone.
     */
    void save(UUID importId, List<RowResult> results);
}
