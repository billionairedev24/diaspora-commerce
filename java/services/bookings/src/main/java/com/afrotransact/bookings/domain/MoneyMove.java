package com.afrotransact.bookings.domain;

import java.util.Objects;

/**
 * A money instruction produced by a booking transition and carried out against payments-service in the same
 * database transaction (so a failed payment call rolls the transition back).
 *
 * @param lineId       PaymentIntent line: {@link #DEPOSIT} or {@link #BALANCE} (null for RELEASE / SETTLE)
 * @param finalCapture release the rest of the authorisation after this capture
 */
public record MoneyMove(MoneyKind kind, String lineId, long amountMinor, boolean finalCapture) {

    public static final String DEPOSIT = "deposit";
    public static final String BALANCE = "balance";

    public MoneyMove {
        Objects.requireNonNull(kind, "kind");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor must not be negative");
        }
    }

    static MoneyMove capture(String lineId, long amount, boolean last) {
        return new MoneyMove(MoneyKind.CAPTURE, lineId, amount, last);
    }

    static MoneyMove release() {
        return new MoneyMove(MoneyKind.RELEASE, null, 0, false);
    }

    static MoneyMove refund(long amount) {
        return new MoneyMove(MoneyKind.REFUND, DEPOSIT, amount, false);
    }

    static MoneyMove settle() {
        return new MoneyMove(MoneyKind.SETTLE, null, 0, false);
    }
}
