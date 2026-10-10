package com.afrotransact.catalog.application;

import java.util.Objects;

/**
 * Rendered label output.
 *
 * @param contentType MIME type ({@code image/svg+xml}, {@code image/png}, {@code text/plain} for ZPL,
 *                    {@code application/pdf})
 * @param fileName    suggested download name
 * @param labels      number of labels printed (copies included)
 */
public record RenderedLabel(String contentType, String fileName, byte[] content, int labels) {

    public RenderedLabel {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(content, "content");
    }
}
