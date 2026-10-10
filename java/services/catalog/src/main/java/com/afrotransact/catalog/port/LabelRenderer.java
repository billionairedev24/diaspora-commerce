package com.afrotransact.catalog.port;

import java.util.List;

import com.afrotransact.catalog.domain.barcode.Label;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.domain.barcode.SheetLayout;

/** Draws labels in one output format. */
public interface LabelRenderer {

    LabelFormat format();

    /** MIME type of the output. */
    String contentType();

    /** True when several labels fit in one output (PDF pages or sheets, ZPL blocks). */
    boolean supportsBatch();

    /** Renders the labels (copies included); {@code layout} only matters for PDF. */
    byte[] render(List<Label> labels, SheetLayout layout);
}
