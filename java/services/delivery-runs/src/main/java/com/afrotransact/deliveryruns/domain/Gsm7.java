package com.afrotransact.deliveryruns.domain;

/**
 * SMS length as carriers count it: GSM-7 characters count 1, the extension table ({@code ^ { } \ [ ~ ] | €})
 * counts 2. A message with any character outside GSM-7 is sent as UCS-2, where one segment holds 70 characters.
 */
public final class Gsm7 {

    private static final String BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String EXTENDED = "^{}\\[~]|€\f";

    private Gsm7() {
    }

    /** GSM-7 septets of {@code text}, or -1 when it needs UCS-2. */
    public static int septets(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (BASIC.indexOf(c) >= 0) {
                n++;
            } else if (EXTENDED.indexOf(c) >= 0) {
                n += 2;
            } else {
                return -1;
            }
        }
        return n;
    }

    /** Whether {@code text} fits one SMS segment (160 GSM-7 septets, or 70 UCS-2 characters). */
    public static boolean fitsOneSegment(String text) {
        int septets = septets(text);
        return septets >= 0 ? septets <= 160 : text.codePointCount(0, text.length()) <= 70;
    }
}
