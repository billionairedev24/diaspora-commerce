package com.afrotransact.orders.domain;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/** AT-&lt;year&gt;-&lt;6 characters&gt; from an alphabet without look-alikes (no 0/O, 1/I). */
public final class DefaultOrderNumberGenerator implements OrderNumberGenerator {

    public static final Pattern FORMAT = Pattern.compile("AT-\\d{4}-[2-9A-HJ-NP-Z]{6}");
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private final RandomGenerator random;

    public DefaultOrderNumberGenerator() {
        this(new SecureRandom());
    }

    public DefaultOrderNumberGenerator(RandomGenerator random) {
        this.random = random;
    }

    @Override
    public String next(int year) {
        StringBuilder sb = new StringBuilder("AT-").append(year).append('-');
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
