package com.afrotransact.catalog.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.brand.BrandNames;
import com.afrotransact.catalog.domain.brand.UnbrandedReason;

/**
 * A detail shoppers need for a product type (e.g. material, allergens, MoCRA number), or a tax fact (a plain-word
 * question whose answer decides how the item is taxed).
 *
 * @param key          stable key stored on products
 * @param label        display label
 * @param options      allowed values; empty means free text
 * @param required     must be filled before submission (tax facts: when {@code requiredWhen} holds)
 * @param multiline    long text (ingredients, INCI)
 * @param kind         how the value is entered; derived from options / multiline when absent
 * @param units        for {@link AttributeKind#NUMBER}: allowed units, the first is the default ("days", "months")
 * @param section      where the product form shows it; {@link AttributeSection#DETAILS} when absent
 * @param help         one-line hint shown under the field and in the upload template; may be null
 * @param applies      whether the product type asks for it; false keeps the definition (and what existing products
 *                     hold) but hides it from the product form and the upload template (absent in stored JSON = true)
 * @param unit         tax facts: the unit of a DECIMAL or PERCENT value ("oz", "%")
 * @param taxFact      the attribute is a tax fact: its key is the fact id sent to tax-returns; its value lives on the
 *                     variant ({@code owner} VARIANT) or the seller's listing ({@code owner} OFFER), never in the
 *                     product's attributes
 * @param owner        VARIANT or OFFER for tax facts; null otherwise
 * @param showWhen     tax facts: expression deciding whether the question is asked (null = always)
 * @param requiredWhen tax facts: expression deciding whether an answer is needed (null = always, when required)
 * @param evidence     a FILE fact that is proof for an exemption claim
 * @param optionLabels tax facts of kind ENUM: the plain label of each option value
 */
public record AttributeDefinition(String key, String label, List<String> options, boolean required,
                                  boolean multiline, AttributeKind kind, List<String> units,
                                  AttributeSection section, String help, Boolean applies, String unit,
                                  boolean taxFact, String owner, Map<String, Object> showWhen,
                                  Map<String, Object> requiredWhen, boolean evidence,
                                  Map<String, String> optionLabels) {

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
        unit = unit == null || unit.isBlank() ? null : unit.trim();
        showWhen = showWhen == null || showWhen.isEmpty() ? null : showWhen;
        requiredWhen = requiredWhen == null || requiredWhen.isEmpty() ? null : requiredWhen;
        optionLabels = optionLabels == null ? Map.of() : Map.copyOf(optionLabels);
    }

    /** A detail (not a tax fact) with every other detail given. */
    public AttributeDefinition(String key, String label, List<String> options, boolean required, boolean multiline,
                               AttributeKind kind, List<String> units, AttributeSection section, String help,
                               Boolean applies) {
        this(key, label, options, required, multiline, kind, units, section, help, applies, null, false, null, null,
                null, false, null);
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

    /** A tax fact: a typed, plain-word question whose answer decides how the item is taxed. */
    public static AttributeDefinition taxFact(String key, String label, AttributeKind kind, String unit, String owner,
                                              String help, List<String> options, Map<String, String> optionLabels,
                                              boolean required, Map<String, Object> when, boolean evidence) {
        return new AttributeDefinition(key, label, options, required, false, kind, List.of(), AttributeSection.TAX,
                help, true, unit, true, owner, when, when, evidence, optionLabels);
    }

    /** The no-brand choices of a brand attribute ({@code options} read as reason codes). */
    public List<UnbrandedReason> unbrandedReasons() {
        return kind == AttributeKind.BRAND
                ? options.stream().map(UnbrandedReason::ofCode).flatMap(Optional::stream).toList()
                : List.of();
    }

    /** The plain label of an option value (the value itself when none is given). */
    public String optionLabel(String value) {
        return optionLabels.getOrDefault(value, value);
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
            case BRAND -> v.length() > BrandNames.MAX_LENGTH ? "Too long" : null;
            case TEXT, MULTILINE -> v.length() > (kind == AttributeKind.MULTILINE ? 5000 : 500) ? "Too long" : null;
            case BOOLEAN, DECIMAL, INTEGER, PERCENT, FILE -> null;
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
