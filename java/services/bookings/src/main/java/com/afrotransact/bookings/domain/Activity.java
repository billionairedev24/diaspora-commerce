package com.afrotransact.bookings.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of a booking's history ("Confirmed · deposit captured"), optionally with the money it moved.
 *
 * @param moneyKind null when the line moved no money
 */
public record Activity(Instant at, Party by, String action, MoneyKind moneyKind, long amountMinor) {

    public Activity {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(action, "action");
    }
}
