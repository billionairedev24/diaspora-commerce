package com.afrotransact.ai.domain.guardrail;

/** Luhn (mod 10) checksum used by payment card numbers. */
public final class Luhn {

    private Luhn() {
    }

    /** True when {@code digits} (digits only, 13–19 long) passes the Luhn check. */
    public static boolean isValidCardNumber(String digits) {
        if (digits == null || digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int d = c - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
