package com.afrotransact.catalog.api;

import java.time.LocalDate;
import java.util.List;

/**
 * What inventory asks catalog in-process for labels: {@code GET /v1/labels/units/{atin}} and {@code POST /v1/labels/batch}
 * (scope {@code catalog:read} or {@code catalog:write}), entered under the calling module's principal. A caller without the
 * scope is refused with {@code ModuleApiException} status 403; label data the catalog rejects is status 422.
 */
public interface LabelApi {

    /** The unit label of a variant ATIN as an SVG, one copy, for the stock owner (the AT-FN code is {@code unitCode}). */
    LabelOutput unitLabel(String atin, String ownerOrgId);

    /** Several labels as one document; {@code format} is PDF or ZPL; one label per page. */
    LabelOutput renderBatch(List<Label> labels, String format);

    /**
     * One label: {@code kind} UNIT (Code 128 of {@code data}) or CUSTOM (cartons).
     *
     * @param lines up to 4 text lines under the title
     */
    record Label(String kind, String data, String caption, String title, List<String> lines, int copies,
                 LocalDate expiry) {

        public Label {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** @param unitCode the AT-FN code of a unit label; null for other labels and for manufacturer barcodes */
    record LabelOutput(String contentType, String fileName, byte[] content, String unitCode) {
    }
}
