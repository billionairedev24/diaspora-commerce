package com.afrotransact.seller.application;

import java.security.SecureRandom;

/** Prefixed random identifiers: {@code org_…}, {@code app_…}. */
final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private Ids() {
    }

    static String org() {
        return "org_" + random(20);
    }

    static String application() {
        return "app_" + random(20);
    }

    private static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
