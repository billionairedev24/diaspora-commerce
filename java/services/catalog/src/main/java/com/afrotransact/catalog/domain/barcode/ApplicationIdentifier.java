package com.afrotransact.catalog.domain.barcode;

import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.Gtin;

/**
 * GS1 application identifiers used on AfroTransact case, pallet and lot labels (GS1 General Specifications §3).
 * {@code fixedLength} AIs belong to the GS1 "predefined length" table (first two digits 00–04, 11–20, 31–36, 41),
 * so they never need an FNC1 separator; the others are variable length up to {@code maxLength}.
 */
public enum ApplicationIdentifier {
    SSCC("00", 18, true, Kind.CHECKED_NUMERIC, "SSCC"),
    GTIN("01", 14, true, Kind.CHECKED_NUMERIC, "GTIN"),
    CONTENT("02", 14, true, Kind.CHECKED_NUMERIC, "CONTENT"),
    BATCH_LOT("10", 20, false, Kind.ALPHANUMERIC, "BATCH/LOT"),
    PRODUCTION_DATE("11", 6, true, Kind.DATE, "PROD DATE"),
    BEST_BEFORE("15", 6, true, Kind.DATE, "BEST BEFORE"),
    EXPIRY("17", 6, true, Kind.DATE, "USE BY"),
    SERIAL("21", 20, false, Kind.ALPHANUMERIC, "SERIAL"),
    COUNT("37", 8, false, Kind.NUMERIC, "COUNT");

    /** GS1 AI encodable character set 82 (alphanumeric AIs). */
    private static final Pattern CSET82 = Pattern.compile("[!\"%&'()*+,\\-./0-9:;<=>?A-Z_a-z]+");
    private static final Pattern DATE = Pattern.compile("\\d{2}(0[1-9]|1[0-2])(0\\d|[12]\\d|3[01])");

    private final String code;
    private final int maxLength;
    private final boolean fixedLength;
    private final Kind kind;
    private final String dataTitle;

    ApplicationIdentifier(String code, int maxLength, boolean fixedLength, Kind kind, String dataTitle) {
        this.code = code;
        this.maxLength = maxLength;
        this.fixedLength = fixedLength;
        this.kind = kind;
        this.dataTitle = dataTitle;
    }

    public String code() {
        return code;
    }

    public int maxLength() {
        return maxLength;
    }

    /** Predefined length: no FNC1 separator is needed after it. */
    public boolean fixedLength() {
        return fixedLength;
    }

    /** GS1 data title printed in the human-readable text of logistics labels. */
    public String dataTitle() {
        return dataTitle;
    }

    /** Returns an error message for {@code value}, or null when it is valid for this AI. */
    public String validate(String value) {
        if (value == null || value.isEmpty()) {
            return "Required";
        }
        return switch (kind) {
            case CHECKED_NUMERIC -> value.length() != maxLength || !Gtin.isValidMod10(value)
                    ? maxLength + " digits with a valid check digit" : null;
            case NUMERIC -> !value.matches("\\d{1," + maxLength + "}") ? "Up to " + maxLength + " digits" : null;
            case DATE -> !DATE.matcher(value).matches() ? "Date as YYMMDD" : null;
            case ALPHANUMERIC -> value.length() > maxLength ? "At most " + maxLength + " characters"
                    : !CSET82.matcher(value).matches() ? "Letters, digits and !\"%&'()*+,-./:;<=>?_ only" : null;
        };
    }

    private enum Kind {
        CHECKED_NUMERIC, NUMERIC, DATE, ALPHANUMERIC
    }
}
