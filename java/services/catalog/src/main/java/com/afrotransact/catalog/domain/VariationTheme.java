package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Objects;

/** A named combination of dimensions, e.g. "Size × Color". At most two dimensions. */
public record VariationTheme(String name, List<VariationDimension> dimensions) {

    public VariationTheme {
        Objects.requireNonNull(name, "name");
        dimensions = List.copyOf(dimensions);
        if (dimensions.isEmpty() || dimensions.size() > 2) {
            throw new IllegalArgumentException("A variation theme has one or two dimensions");
        }
    }

    public VariationDimension dimension(String dimensionName) {
        return dimensions.stream().filter(d -> d.name().equals(dimensionName)).findFirst().orElse(null);
    }
}
