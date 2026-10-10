package com.afrotransact.catalog.application;

import java.util.Objects;

/**
 * Rendered label output.
 *
 * @param contentType MIME type ({@code image/svg+xml}, {@code image/png}, {@code text/plain} for ZPL,
 *                    {@code application/pdf})
 * @param fileName    suggested download name
 * @param labels      number of labels printed (copies included)
 * @param unitCode    the AT-FN code of a unit label (returned as {@code X-Unit-Label-Code}); null for other labels
 *                    and for manufacturer barcodes
 */
public record RenderedLabel(String contentType, String fileName, byte[] content, int labels, String unitCode) {

    public RenderedLabel {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(content, "content");
    }

    /** Output without a unit-label code. */
    public RenderedLabel(String contentType, String fileName, byte[] content, int labels) {
        this(contentType, fileName, content, labels, null);
    }

    public RenderedLabel withUnitCode(String code) {
        return new RenderedLabel(contentType, fileName, content, labels, code);
    }
}
