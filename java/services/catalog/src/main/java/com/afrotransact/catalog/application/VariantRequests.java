package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.port.ProductImportRepository.RowResult;

/**
 * Files a NEW_VARIANT request for a size or colour of a page that already exists, for a row of a bulk upload (S393): the
 * seller's file had some children of a parent group on the page and this one not. The request waits for staff like any
 * suggestion (S385); it never creates a page and never changes one. The row is recorded HELD with a plain note, and the
 * seller sees "We sent it for review".
 */
public interface VariantRequests {

    /**
     * @param parentAtin the page the matched siblings of the row are on
     * @return HELD with the request filed, or FAILED with the reason (no options named, no open offer on the page yet,
     *         a request for this SKU already waiting counts as filed)
     */
    RowResult file(ProductImport upload, StoredRow row, String parentAtin);
}
