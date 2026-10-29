package com.afrotransact.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Test factory for lots. */
final class Lots {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private Lots() {
    }

    static Lot lot(String lotNo, LocalDate exp, int onHand, int reserved, LotStatus status) {
        return lot(lotNo, exp, onHand, reserved, status, LotCost.usd(192, TODAY));
    }

    static Lot lot(String lotNo, LocalDate exp, int onHand, int reserved, LotStatus status, LotCost cost) {
        return new Lot(UUID.randomUUID(), "AT-JOL-6", lotNo, "HOU-FC", "org_afrotransact_retail", "A-01-01", onHand,
                reserved, exp == null ? null : exp.minusMonths(18), exp, Instant.parse("2026-05-20T10:00:00Z"), cost,
                status, null, MarkdownFlag.NONE, "GRN-1", 0);
    }

    static Lot sellable(String lotNo, LocalDate exp, int onHand) {
        return lot(lotNo, exp, onHand, 0, LotStatus.SELLABLE);
    }

    /** A sellable lot bought in Mexican pesos: MXN 120.00 a unit at 0.0577 USD = $6.92. */
    static Lot pesoLot(String lotNo, LocalDate exp, int onHand) {
        LotCost cost = LotCost.valued(12_000, "MXN", new BigDecimal("0.0577"), TODAY.minusDays(3), RateSource.ENTERED,
                null);
        return lot(lotNo, exp, onHand, 0, LotStatus.SELLABLE, cost);
    }
}
