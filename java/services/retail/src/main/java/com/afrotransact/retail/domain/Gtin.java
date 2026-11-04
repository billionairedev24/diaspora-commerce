package com.afrotransact.retail.domain;

/**
 * GTIN-12 (UPC-A) numbers issued from a prefix: prefix + item reference + check digit. The default prefix {@code 04000} is
 * GS1's internal-use range (UPC-A numbers starting 04), so issued numbers can never clash with a manufacturer's real barcode
 * but are only meaningful inside AfroTransact; use a licensed GS1 company prefix if items are sold on other channels. Six-digit
 * item references (e.g. 04000 200061 → 040002000615).
 */
public final class Gtin {

    private Gtin() {
    }

    /** Issues the GTIN-12 for an item reference under a UPC company prefix. */
    public static String issue(String companyPrefix, long itemReference) {
        if (companyPrefix == null || !companyPrefix.matches("\\d{5,10}")) {
            throw new IllegalArgumentException("GS1 company prefix must be 5–10 digits");
        }
        int refDigits = 11 - companyPrefix.length();
        String ref = String.valueOf(itemReference);
        if (itemReference < 0 || ref.length() > refDigits) {
            throw new RetailRuleViolation("retail.gtin-exhausted", "Item reference " + itemReference
                    + " does not fit the GS1 prefix " + companyPrefix);
        }
        String body = companyPrefix + "0".repeat(refDigits - ref.length()) + ref;
        return body + checkDigit(body);
    }

    /** GS1 mod-10 check digit over the body (weights 3,1 from the right). */
    public static int checkDigit(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int digit = body.charAt(body.length() - 1 - i) - '0';
            if (digit < 0 || digit > 9) {
                throw new IllegalArgumentException("GTIN body must be digits");
            }
            sum += digit * (i % 2 == 0 ? 3 : 1);
        }
        return (10 - sum % 10) % 10;
    }

    /** True when {@code gtin} (8, 12, 13 or 14 digits) carries a valid check digit. */
    public static boolean valid(String gtin) {
        if (gtin == null || !gtin.matches("\\d{8}|\\d{12,14}")) {
            return false;
        }
        return checkDigit(gtin.substring(0, gtin.length() - 1)) == gtin.charAt(gtin.length() - 1) - '0';
    }

    /** Zero-padded GTIN-14 form used on case labels (GS1-128 AI 01). */
    public static String toGtin14(String gtin) {
        return "0".repeat(14 - gtin.length()) + gtin;
    }
}
