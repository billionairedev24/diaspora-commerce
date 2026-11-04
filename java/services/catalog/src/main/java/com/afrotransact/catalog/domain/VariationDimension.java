package com.afrotransact.catalog.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One axis of a variation theme, e.g. Size with XS–3XL.
 *
 * @param options           the values the category offers; the only allowed values unless {@code allowCustomValues}
 * @param allowCustomValues the seller may use values of their own besides {@code options} (sizes such as "UK 12" or
 *                          "Age 6-7", colours such as "Indigo adire"); the options are then suggestions. Absent in
 *                          stored JSON = false.
 */
public record VariationDimension(String name, List<String> options, Boolean allowCustomValues) {

    public VariationDimension {
        Objects.requireNonNull(name, "name");
        options = options == null ? List.of() : List.copyOf(options);
        allowCustomValues = allowCustomValues != null && allowCustomValues;
    }

    /** A dimension that only accepts its options. */
    public VariationDimension(String name, List<String> options) {
        this(name, options, false);
    }

    /** The option that matches {@code value} ignoring case and surrounding spaces: the category's own spelling. */
    public Optional<String> suggestion(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String wanted = value.strip().toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.strip().toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }
}
