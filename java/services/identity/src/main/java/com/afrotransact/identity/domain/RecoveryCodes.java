package com.afrotransact.identity.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Single-use recovery codes ({@code xxxxx-xxxxx}), shown once and stored only as hashes. */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    public static List<String> generate() {
        List<String> codes = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            codes.add(block() + "-" + block());
        }
        return codes;
    }

    public static String hash(String code) {
        return SecretHashing.sha256Hex(normalise(code));
    }

    public static String normalise(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private static String block() {
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
