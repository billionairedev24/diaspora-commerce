package com.afrotransact.catalog.domain.barcode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.afrotransact.catalog.domain.CatalogRuleViolation;

/**
 * A validated GS1 element string: application identifiers with their data, in carrier order. Predefined-length AIs
 * come first and variable-length AIs last, so an FNC1 separator is only needed between variable-length fields
 * (never after the last one).
 */
public record Gs1ElementString(List<Element> elements) {

    /** GS1-128 carries at most 48 data characters (AIs, data and FNC1 separators; not the leading FNC1). */
    public static final int MAX_DATA_CHARACTERS = 48;

    public Gs1ElementString {
        elements = List.copyOf(elements);
    }

    /** One AI and its data. */
    public record Element(ApplicationIdentifier ai, String data) {

        public Element {
            Objects.requireNonNull(ai, "ai");
            Objects.requireNonNull(data, "data");
        }
    }

    /**
     * Validates and orders the elements. Field errors are keyed by AI code ({@code ai01}, {@code ai10} …).
     *
     * @throws CatalogRuleViolation when data is invalid, an AI repeats, a pairing rule is broken or the string is
     *                              longer than {@value #MAX_DATA_CHARACTERS} characters
     */
    public static Gs1ElementString of(Map<ApplicationIdentifier, String> values) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<Element> fixed = new ArrayList<>();
        List<Element> variable = new ArrayList<>();
        values.forEach((ai, value) -> {
            String error = ai.validate(value);
            if (error != null) {
                errors.put("ai" + ai.code(), "(" + ai.code() + ") " + error);
            } else {
                (ai.fixedLength() ? fixed : variable).add(new Element(ai, value));
            }
        });
        if (values.isEmpty()) {
            errors.put("ai", "At least one application identifier is required");
        }
        Set<ApplicationIdentifier> present = values.isEmpty() ? EnumSet.noneOf(ApplicationIdentifier.class)
                : EnumSet.copyOf(values.keySet());
        if (present.contains(ApplicationIdentifier.GTIN) && present.contains(ApplicationIdentifier.CONTENT)) {
            errors.put("ai02", "(02) cannot be used together with (01)");
        }
        if (present.contains(ApplicationIdentifier.COUNT) && !present.contains(ApplicationIdentifier.CONTENT)) {
            errors.put("ai37", "(37) count needs (02) content GTIN");
        }
        if (present.contains(ApplicationIdentifier.CONTENT) && !present.contains(ApplicationIdentifier.SSCC)) {
            errors.put("ai02", "(02) content GTIN is only used on a logistic unit with (00) SSCC");
        }
        if (!errors.isEmpty()) {
            throw new CatalogRuleViolation("Invalid GS1 application identifiers", errors);
        }
        List<Element> ordered = new ArrayList<>(fixed);
        ordered.addAll(variable);
        Gs1ElementString s = new Gs1ElementString(ordered);
        if (s.dataLength() > MAX_DATA_CHARACTERS) {
            throw new CatalogRuleViolation("A GS1-128 barcode holds at most " + MAX_DATA_CHARACTERS
                    + " characters (this one needs " + s.dataLength() + "); shorten the lot or serial",
                    Map.of("ai", "Too long for one GS1-128 barcode"));
        }
        return s;
    }

    /**
     * Carrier input for Code 128: FNC1, then each AI and its data, with an FNC1 separator after every variable-length
     * field except the last.
     */
    public int[] carrierInput() {
        List<Integer> out = new ArrayList<>();
        out.add(Code128.FNC1);
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            (e.ai().code() + e.data()).chars().forEach(out::add);
            if (!e.ai().fixedLength() && i < elements.size() - 1) {
                out.add(Code128.FNC1);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Human-readable interpretation: {@code (01)09501101530003(17)261231(10)LOT42}. */
    public String humanReadable() {
        StringBuilder sb = new StringBuilder();
        for (Element e : elements) {
            sb.append('(').append(e.ai().code()).append(')').append(e.data());
        }
        return sb.toString();
    }

    /**
     * Unbracketed element string with {@code <GS>} (ASCII 29) where an FNC1 separator is encoded — what a scanner
     * transmits after the {@code ]C1} symbology identifier.
     */
    public String transmitted() {
        StringBuilder sb = new StringBuilder();
        for (int c : carrierInput()) {
            if (c == Code128.FNC1) {
                if (!sb.isEmpty()) {
                    sb.append((char) 29);
                }
            } else {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    /** Data value of {@code ai}, or null. */
    public String value(ApplicationIdentifier ai) {
        return elements.stream().filter(e -> e.ai() == ai).map(Element::data).findFirst().orElse(null);
    }

    private int dataLength() {
        return carrierInput().length - 1;
    }
}
