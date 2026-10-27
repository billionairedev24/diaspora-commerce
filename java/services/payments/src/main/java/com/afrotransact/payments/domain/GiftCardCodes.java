package com.afrotransact.payments.domain;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Gift card codes: 16 characters from a 32-letter alphabet without look-alikes (80 bits), shown as
 * {@code XXXX-XXXX-XXXX-XXXX}. Guessing is hopeless on entropy alone; the redeem rate limit is the second wall.
 */
public final class GiftCardCodes {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 16;

    private GiftCardCodes() {
    }

    /** A new random code in display form. */
    public static String generate(SecureRandom random) {
        StringBuilder sb = new StringBuilder(LENGTH + 3);
        for (int i = 0; i < LENGTH; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** What the hash is taken over: upper case, letters and digits only. Null when the input cannot be a code. */
    public static String normalise(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(LENGTH);
        for (char c : input.toUpperCase(Locale.ROOT).toCharArray()) {
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

    public static String last4(String normalised) {
        return normalised.substring(normalised.length() - 4);
    }
}
