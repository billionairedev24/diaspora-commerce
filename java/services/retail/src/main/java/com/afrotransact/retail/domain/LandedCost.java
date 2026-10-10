package com.afrotransact.retail.domain;

import java.util.List;

/** Landed-cost breakdown of a receipt or shipment in base currency; totals equal the sum of the lines exactly. */
public record LandedCost(String currency, List<LandedCostLine> lines, long fobMinor, long dutyMinor,
                         long chargesMinor, long totalMinor) {

    public LandedCost {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public LandedCostLine line(int lineNo) {
        return lines.stream().filter(l -> l.lineNo() == lineNo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No landed cost for line " + lineNo));
    }
}
