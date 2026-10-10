package com.afrotransact.catalog.domain.barcode;

import java.time.LocalDate;
import java.util.List;

/**
 * What the caller wants on a label. Which fields apply depends on {@code kind} (see {@link LabelPolicy}).
 *
 * @param symbology      optional override (CUSTOM labels must set it)
 * @param data           Code 128 text: an ATIN, AT-FN code, SKU or bin
 * @param gtin           manufacturer GTIN (EAN-13 retail labels; AI 01 on case labels, AI 02 on pallets)
 * @param sscc           18-digit SSCC (AI 00, pallet labels)
 * @param lot            batch / lot (AI 10)
 * @param productionDate AI 11
 * @param bestBefore     AI 15
 * @param expiry         AI 17
 * @param serial         AI 21
 * @param count          trade items on a pallet (AI 37, with AI 02)
 */
public record LabelDraft(LabelKind kind, Symbology symbology, String data, String gtin, String sscc, String lot,
                         LocalDate productionDate, LocalDate bestBefore, LocalDate expiry, String serial,
                         Integer count, String caption, String title, List<String> lines, Integer copies) {

    public LabelDraft {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
