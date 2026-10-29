package com.afrotransact.catalog.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A detail shoppers need for a product type (e.g. material, allergens, MoCRA number).
 *
 * @param key       stable key stored on products
 * @param label     display label
 * @param options   allowed values; empty means free text
 * @param required  must be filled before submission
 * @param multiline long text (ingredients, INCI)
 * @param kind      how the value is entered; derived from options / multiline when absent
 * @param units     for {@link AttributeKind#NUMBER}: allowed units, the first is the default ("days", "months")
 * @param section   where the product form shows it; {@link AttributeSection#DETAILS} when absent
 * @param help      one-line hint shown under the field and in the upload template; may be null
 * @param applies   whether the product type asks for it; false keeps the definition (and what existing products
 *                  hold) but hides it from the product form and the upload template (absent in stored JSON = true)
 */
public record AttributeDefinition(String key, String label, List<String> options, boolean required,
                                  boolean multiline, AttributeKind kind, List<String> units,
                                  AttributeSection section, String help, Boolean applies) {

    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile("^(-?\\d+(?:[.,]\\d+)?)\\s*(.*)$");

    public AttributeDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        options = options == null ? List.of() : List.copyOf(options);
        units = units == null ? List.of() : List.copyOf(units);
        if (kind == null) {
            kind = !options.isEmpty() ? AttributeKind.ENUM : multiline ? AttributeKind.MULTILINE : AttributeKind.TEXT;
        }
        section = section == null ? AttributeSection.DETAILS : section;
        help = help == null || help.isBlank() ? null : help.trim();
        applies = applies == null || applies;
    }

    /** An attribute that applies, with every other detail given. */
    public AttributeDefinition(String key, String label, List<String> options, boolean required, boolean multiline,
                               AttributeKind kind, List<String> units, AttributeSection section, String help) {
        this(key, label, options, required, multiline, kind, units, section, help, null);
    }

    /** A details attribute whose kind follows its options and multiline flag. */
    public AttributeDefinition(String key, String label, List<String> options, boolean required, boolean multiline) {
        this(key, label, options, required, multiline, null, null, null, null, null);
    }

    public boolean accepts(String value) {
        return problem(value) == null;
    }

    /** Why the value is not acceptable, or null when it is. */
    public String problem(String value) {
        if (value == null || value.isBlank()) {
            return required ? "Required" : null;
        }
        String v = value.trim();
        return switch (kind) {
            case ENUM -> options.isEmpty() || options.contains(v) ? null : "Choose one of " + options;
            case NUMBER -> numberProblem(v);
            case TEXT, MULTILINE -> v.length() > (kind == AttributeKind.MULTILINE ? 5000 : 500) ? "Too long" : null;
        };
    }

    private String numberProblem(String v) {
        Matcher m = NUMBER_WITH_UNIT.matcher(v);
        if (!m.matches()) {
            return "Enter a number" + (units.isEmpty() ? "" : " and a unit (" + String.join(", ", units) + ")");
        }
        BigDecimal n = new BigDecimal(m.group(1).replace(',', '.'));
        if (n.signum() < 0) {
            return "Cannot be negative";
        }
        String unit = m.group(2).trim();
        if (unit.isEmpty() || units.isEmpty()) {
            return unit.isEmpty() ? null : "No unit expected";
        }
        return units.stream().anyMatch(u -> u.equalsIgnoreCase(unit)) ? null
                : "Unit must be one of " + String.join(", ", units);
    }

    /** The stored form of a number and unit ("12 months"); the default unit when none is given. */
    public String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (kind != AttributeKind.NUMBER || units.isEmpty()) {
            return v;
        }
        Matcher m = NUMBER_WITH_UNIT.matcher(v);
        if (!m.matches()) {
            return v;
        }
        String unit = m.group(2).trim();
        String canonical = unit.isEmpty() ? units.getFirst()
                : units.stream().filter(u -> u.equalsIgnoreCase(unit)).findFirst().orElse(unit.toLowerCase(Locale.ROOT));
        return m.group(1).replace(',', '.') + " " + canonical;
    }
}
