package com.afrotransact.catalog.application;

import java.util.List;

import com.afrotransact.catalog.domain.barcode.LabelDraft;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.domain.barcode.SheetLayout;

/**
 * S032: barcode labels for ATINs, GTINs, lots and logistic units: Code 128, GS1-128 and EAN-13 as SVG, PNG, ZPL or
 * PDF. Needs {@code catalog:write} (sellers, retail staff) or {@code catalog:read} (service clients such as
 * fulfilment).
 */
public interface LabelService {

    /** Maximum labels in one batch request, and printed labels (copies included) in one PDF / ZPL output. */
    int MAX_BATCH = 500;
    int MAX_PRINTED = 2000;

    /** One label in any format. */
    RenderedLabel render(LabelDraft draft, LabelFormat format, SheetLayout layout);

    /** Several labels as one PDF (pages or sheets) or one ZPL stream. */
    RenderedLabel renderBatch(List<LabelDraft> drafts, LabelFormat format, SheetLayout layout);

    /**
     * The unit label of a product or variant ATIN. Platform (AfroTransact Retail) stock with a vendor GTIN keeps the
     * manufacturer barcode (EAN-13, or GS1-128 (01) for GTIN-8/14); every other unit gets the stock owner's AT-FN code
     * ({@code ATF…}, Code 128), issued once per owner and variant so units of different sellers never mix.
     *
     * @param ownerOrgId stock owner; staff and service clients may print for any organisation (default: the
     *                   product's owner), sellers always print for their own organisation
     */
    RenderedLabel unitLabel(String atin, String ownerOrgId, LabelFormat format, int copies);
}
