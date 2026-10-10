package com.afrotransact.inventory.domain;

import java.util.List;

/** One reserved order line at one node, with the lots allocated to it (FEFO). */
public record ReservationLine(int lineNo, String sku, String nodeId, int qty, List<LotAllocation> allocations) {

    public ReservationLine {
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }

    public long costMinor() {
        return allocations.stream().mapToLong(LotAllocation::costMinor).sum();
    }
}
