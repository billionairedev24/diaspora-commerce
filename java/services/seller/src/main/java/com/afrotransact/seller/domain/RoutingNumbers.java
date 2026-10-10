package com.afrotransact.seller.domain;

/** ABA routing transit numbers: nine digits, a valid Federal Reserve prefix and the 3-7-1 checksum. */
public final class RoutingNumbers {

    private RoutingNumbers() {
    }

    public static boolean isValid(String raw) {
        if (raw == null || !raw.matches("\\d{9}")) {
            return false;
        }
        int prefix = Integer.parseInt(raw.substring(0, 2));
        boolean validPrefix = prefix <= 12 || (prefix >= 21 && prefix <= 32) || (prefix >= 61 && prefix <= 72)
                || prefix == 80;
        if (!validPrefix) {
            return false;
        }
        int[] d = raw.chars().map(c -> c - '0').toArray();
        int sum = 3 * (d[0] + d[3] + d[6]) + 7 * (d[1] + d[4] + d[7]) + (d[2] + d[5] + d[8]);
        return sum % 10 == 0;
    }
}
