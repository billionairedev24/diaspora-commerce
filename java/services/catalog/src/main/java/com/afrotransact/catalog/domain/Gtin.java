package com.afrotransact.catalog.domain;

import java.util.Objects;

/**
 * GS1 Global Trade Item Number (GTIN-8, UPC-A/GTIN-12, EAN-13/GTIN-13 or GTIN-14), including ISBN-13.
 * Only digits are kept; the mod-10 check digit must be valid.
 */
public record Gtin(String value) {

    public Gtin {
        Objects.requireNonNull(value, "value");
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid GTIN " + value);
        }
    }

    public static Gtin of(String raw) {
        return new Gtin(digits(raw));
    }

    public static String digits(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    /** True for 8, 12, 13 or 14 digits with a correct GS1 check digit. */
    public static boolean isValid(String digits) {
        if (digits == null || !digits.matches("\\d{8}|\\d{12,14}")) {
            return false;
        }
        int expected = checkDigit(digits.substring(0, digits.length() - 1));
        return expected == digits.charAt(digits.length() - 1) - '0';
    }

    /** True when {@code digits} (any length, e.g. an 18-digit SSCC) ends with a correct GS1 mod-10 check digit. */
    public static boolean isValidMod10(String digits) {
        if (digits == null || !digits.matches("\\d{2,}")) {
            return false;
        }
        return checkDigit(digits.substring(0, digits.length() - 1)) == digits.charAt(digits.length() - 1) - '0';
    }

    /** GS1 mod-10 check digit for the given body (all digits except the last). */
    public static int checkDigit(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int d = body.charAt(body.length() - 1 - i) - '0';
            sum += i % 2 == 0 ? d * 3 : d;
        }
        return (10 - sum % 10) % 10;
    }

    /** Suggests the correct check digit for a code whose body is right but whose last digit is wrong. */
    public static String suggestCorrection(String digits) {
        if (digits == null || !digits.matches("\\d{8}|\\d{12,14}")) {
            return null;
        }
        String body = digits.substring(0, digits.length() - 1);
        return body + checkDigit(body);
    }

    /** The GTIN left-padded with zeros to 14 digits (AI 01, GS1 data carriers, cache keys). */
    public String toGtin14() {
        return "0".repeat(14 - value.length()) + value;
    }

    public boolean isIsbn13() {
        return value.length() == 13 && (value.startsWith("978") || value.startsWith("979"));
    }
}
