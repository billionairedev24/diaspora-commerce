package com.afrotransact.catalog.domain;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads what a seller typed as a barcode into the 14-digit match key (S381): spaces and dashes are dropped, an ISBN-10
 * becomes its ISBN-13, the GS1 check digit is verified, and store-use and coupon numbers are refused. UPC-A (12), EAN-13
 * and GTIN-14 of one item share one key, so the UPC {@code 012345678905} and the EAN {@code 0012345678905} meet.
 */
public final class GtinReader {

    private static final Pattern ISBN10 = Pattern.compile("[0-9]{9}[0-9Xx]");
    private static final String INVALID = "That barcode does not look right.";

    private GtinReader() {
    }

    /** What the reader made of the input. */
    public enum Status {
        /** Nothing was typed. */
        EMPTY,
        /** Not a barcode: wrong length or check digit. */
        INVALID,
        /** A store-use or coupon number, not a product barcode. */
        RESTRICTED,
        /** A barcode with a correct check digit. */
        VALID
    }

    /**
     * @param digits     the cleaned digits (an ISBN-10 already converted to its ISBN-13)
     * @param gtin14     the match key, null unless valid or restricted
     * @param suggestion a corrected code when only the check digit is wrong
     * @param message    plain words for the seller (null when valid)
     */
    public record Reading(Status status, String digits, String gtin14, String suggestion, String message) {

        public boolean valid() {
            return status == Status.VALID;
        }
    }

    /**
     * @param reservedPrefixes numbers that are not product barcodes (store-use and coupon prefixes), compared with the
     *                         start of the GTIN-13 form (or of a GTIN-8)
     */
    public static Reading read(String raw, Collection<String> reservedPrefixes) {
        String cleaned = clean(raw);
        if (cleaned.isEmpty()) {
            return new Reading(Status.EMPTY, "", null, null, "Enter the barcode, or say the item has none.");
        }
        if (isIsbn10(cleaned)) {
            if (!isbn10Valid(cleaned)) {
                return new Reading(Status.INVALID, cleaned, null, null, INVALID + " Check the ISBN digits.");
            }
            cleaned = isbn10To13(cleaned);
        }
        String digits = Gtin.digits(cleaned);
        if (!Gtin.isValid(digits)) {
            String fix = Gtin.suggestCorrection(digits);
            String message = fix == null ? INVALID + " Use 8, 12, 13 or 14 digits."
                    : INVALID + " Did you mean " + fix + "?";
            return new Reading(Status.INVALID, digits, null, fix, message);
        }
        String key = key14(digits);
        if (restricted(digits, reservedPrefixes)) {
            return new Reading(Status.RESTRICTED, digits, key, null,
                    "This number is for store use or coupons, not a product barcode.");
        }
        return new Reading(Status.VALID, digits, key, null, null);
    }

    /** The 14-digit key of a barcode of 8, 12, 13 or 14 digits: left-padded with zeros. */
    public static String key14(String digits) {
        return "0".repeat(Math.max(0, 14 - digits.length())) + digits;
    }

    /** Spaces and dashes dropped; an ISBN-10 keeps its trailing X; anything else keeps its digits only. */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String compact = raw.replaceAll("[\\s-]", "");
        if (isIsbn10(compact)) {
            return compact.toUpperCase(Locale.ROOT);
        }
        return Gtin.digits(compact);
    }

    static boolean isIsbn10(String s) {
        return ISBN10.matcher(s).matches();
    }

    static boolean isbn10Valid(String isbn10) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = isbn10.charAt(i);
            int d = c == 'X' ? 10 : c - '0';
            if (c == 'X' && i != 9) {
                return false;
            }
            sum += (10 - i) * d;
        }
        return sum % 11 == 0;
    }

    static String isbn10To13(String isbn10) {
        String body = "978" + isbn10.substring(0, 9);
        return body + Gtin.checkDigit(body);
    }

    /** The reserved prefixes apply to the GTIN-13 form of the code (a UPC-A starts with the zero it is padded with). */
    static boolean restricted(String digits, Collection<String> reservedPrefixes) {
        String form = digits.length() == 8 ? digits : key14(digits).substring(1);
        for (String prefix : reservedPrefixes) {
            if (!prefix.isEmpty() && form.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
