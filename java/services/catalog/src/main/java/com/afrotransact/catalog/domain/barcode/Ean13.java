package com.afrotransact.catalog.domain.barcode;

import com.afrotransact.catalog.domain.Gtin;

/** EAN-13 (and UPC-A as EAN-13 with a leading zero), matching {@code design/at-barcode.js}. */
final class Ean13 {

    /** Quiet zone on each side, in modules (EAN-13 needs 11 on the left, 7 on the right; we use 11 on both). */
    static final int QUIET_ZONE = 11;

    private static final String[] L = {"0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111",
        "0111011", "0110111", "0001011"};
    private static final String[] G = {"0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101",
        "0010001", "0001001", "0010111"};
    private static final String[] PARITY = {"LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL",
        "LGLGLG", "LGLGGL", "LGGLGL"};

    private Ean13() {
    }

    /** 13 digits for a GTIN-12 (UPC-A) or GTIN-13 with a valid check digit, or a GTIN-14 with indicator 0. */
    static String digits(String gtin) {
        String g = Gtin.digits(gtin);
        if (!Gtin.isValid(g)) {
            throw new IllegalArgumentException("EAN-13 needs a valid GTIN-12 or GTIN-13 (got " + gtin + ")");
        }
        if (g.length() == 12) {
            return "0" + g;
        }
        if (g.length() == 14 && g.charAt(0) == '0') {
            return g.substring(1);
        }
        if (g.length() != 13) {
            throw new IllegalArgumentException("EAN-13 needs a GTIN-12 or GTIN-13; use GS1-128 for " + g.length()
                    + "-digit GTINs");
        }
        return g;
    }

    /** 95-module pattern (guards included), without quiet zones. */
    static String modules(String digits13) {
        int first = digits13.charAt(0) - '0';
        StringBuilder m = new StringBuilder("101");
        for (int i = 1; i <= 6; i++) {
            int d = digits13.charAt(i) - '0';
            m.append(PARITY[first].charAt(i - 1) == 'L' ? L[d] : G[d]);
        }
        m.append("01010");
        for (int i = 7; i <= 12; i++) {
            m.append(invert(L[digits13.charAt(i) - '0']));
        }
        return m.append("101").toString();
    }

    private static String invert(String pattern) {
        StringBuilder sb = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            sb.append(pattern.charAt(i) == '1' ? '0' : '1');
        }
        return sb.toString();
    }
}
