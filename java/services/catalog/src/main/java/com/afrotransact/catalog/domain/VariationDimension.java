package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Objects;

/** One axis of a variation theme, e.g. Size with XS–3XL. */
public record VariationDimension(String name, List<String> options) {

    public VariationDimension {
        Objects.requireNonNull(name, "name");
        options = options == null ? List.of() : List.copyOf(options);
    }
}
