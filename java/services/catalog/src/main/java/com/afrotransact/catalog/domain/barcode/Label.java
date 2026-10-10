package com.afrotransact.catalog.domain.barcode;

import java.util.List;
import java.util.Objects;

/**
 * A label ready to render: one barcode, a title and caption lines on a label of {@code kind}'s size.
 *
 * @param caption small text over the title (e.g. "AFROTRANSACT UNIT LABEL"), may be null
 * @param lines   extra lines under the title (variant, lot and dates in words, price …)
 * @param copies  how many times the label is printed (1–1000)
 */
public record Label(LabelKind kind, BarcodeSymbol symbol, String caption, String title, List<String> lines,
                    int copies) {

    public static final int MAX_COPIES = 1000;

    public Label {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(symbol, "symbol");
        title = title == null ? "" : title.strip();
        lines = lines == null ? List.of() : lines.stream().filter(Objects::nonNull).map(String::strip)
                .filter(l -> !l.isEmpty()).limit(4).toList();
        if (copies < 1 || copies > MAX_COPIES) {
            throw new IllegalArgumentException("Copies must be between 1 and " + MAX_COPIES);
        }
    }

    public double widthMm() {
        return kind.widthMm();
    }

    public double heightMm() {
        return kind.heightMm();
    }
}
