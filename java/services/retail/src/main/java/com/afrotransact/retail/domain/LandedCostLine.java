package com.afrotransact.retail.domain;

import java.util.List;

/** Landed cost of one line: FOB + duty + allocated charges, and the resulting cost per unit (base currency). */
public record LandedCostLine(int lineNo, String sku, int qty, long fobValueMinor, long dutyMinor,
                             List<ChargeShare> charges, long totalMinor, long unitCostMinor) {

    public LandedCostLine {
        charges = charges == null ? List.of() : List.copyOf(charges);
    }
}
