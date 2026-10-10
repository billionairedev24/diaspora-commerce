package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Test factory for lots. */
final class Lots {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private Lots() {
    }

    static Lot lot(String lotNo, LocalDate exp, int onHand, int reserved, LotStatus status) {
        return new Lot(UUID.randomUUID(), "AT-JOL-6", lotNo, "HOU-FC", "org_afrotransact_retail", "A-01-01", onHand,
                reserved, exp == null ? null : exp.minusMonths(18), exp, Instant.parse("2026-05-20T10:00:00Z"), 192,
                "USD", status, null, MarkdownFlag.NONE, "GRN-1", 0);
    }

    static Lot sellable(String lotNo, LocalDate exp, int onHand) {
        return lot(lotNo, exp, onHand, 0, LotStatus.SELLABLE);
    }
}
