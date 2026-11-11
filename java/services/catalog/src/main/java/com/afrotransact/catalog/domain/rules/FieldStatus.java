package com.afrotransact.catalog.domain.rules;

import java.util.Arrays;
import java.util.Locale;

/**
 * What a listing field is for one kind of product (the cells of the matrix in {@code docs/engineering/listing-rules.md}).
 * The JSON code is the lower case name.
 */
public enum FieldStatus {
    /** Must be answered. */
    REQUIRED,
    /** Must be answered, or the seller states why it cannot be (an exemption with a reason). */
    REQUIRED_OR_EXEMPTION,
    /** An upload slot; the approval engine reviews the file, the listing is not blocked by it. */
    DOCUMENT,
    /** Shown, may stay empty. */
    OPTIONAL,
    /** Not shown, not required; a submitted value is ignored. */
    HIDDEN;

    /** The code used in rules.json and the API ({@code required_or_exemption}). */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The status of a rules.json code; throws on an unknown one so a typo in the source fails loudly. */
    public static FieldStatus ofCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown field status: " + code));
    }

    /** True when the form shows the field. */
    public boolean shown() {
        return this != HIDDEN;
    }

    /** True when an answer is expected (required, or an exemption in its place). */
    public boolean expectsAnswer() {
        return this == REQUIRED || this == REQUIRED_OR_EXEMPTION;
    }
}
