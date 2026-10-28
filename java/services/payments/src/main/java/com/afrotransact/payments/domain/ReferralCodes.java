package com.afrotransact.payments.domain;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Referral codes: eight characters from an alphabet of consonants and digits without look-alikes (27 symbols, about 38
 * bits), shown as {@code XXXX-XXXX}. They are random, never derived from the customer, and have no vowels, so a code
 * cannot spell a word (nothing rude or misleading can be shared as a vanity code). They are not secret, only unguessable
 * enough that nobody can enumerate customers by trying codes; the sign-up form is rate limited like every public form.
 */
public final class ReferralCodes {

    static final String ALPHABET = "BCDFGHJKMNPQRSTVWXZ23456789";
    public static final int LENGTH = 8;

    private ReferralCodes() {
    }

    /** A new random code in storage form (no separator). */
    public static String generate(SecureRandom random) {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Upper case, separators removed. Null when the input cannot be a referral code. */
    public static String normalise(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(LENGTH);
        for (char c : input.trim().toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '-' || c == ' ') {
                continue;
            }
            if (ALPHABET.indexOf(c) < 0) {
                return null;
            }
            sb.append(c);
        }
        return sb.length() == LENGTH ? sb.toString() : null;
    }

    /** {@code KDM7XR3Q} as {@code KDM7-XR3Q}. */
    public static String display(String code) {
        return code.substring(0, LENGTH / 2) + "-" + code.substring(LENGTH / 2);
    }
}
