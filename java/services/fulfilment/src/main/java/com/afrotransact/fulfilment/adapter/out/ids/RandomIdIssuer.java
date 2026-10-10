package com.afrotransact.fulfilment.adapter.out.ids;

import java.security.SecureRandom;

import com.afrotransact.fulfilment.port.IdIssuer;
import org.springframework.stereotype.Component;

/** Random base-36 identifiers (36^9 ≈ 10^14 labels; collisions are refused by unique constraints). */
@Component
class RandomIdIssuer implements IdIssuer {

    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private final SecureRandom random = new SecureRandom();

    @Override
    public String packageLabel() {
        return "ATS" + chars(9);
    }

    @Override
    public String routeId() {
        return "RT-" + chars(8);
    }

    private String chars(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
