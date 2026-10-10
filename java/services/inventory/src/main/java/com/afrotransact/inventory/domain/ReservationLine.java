package com.afrotransact.inventory.domain;

import java.util.List;
import java.util.Set;

/**
 * One reserved order line at one node, with the lots allocated to it (FEFO).
 *
 * @param group           caller's grouping key (orders sends its order line id, so a shipment's lines can be
 *                        committed or released on their own); null for reservations made without groups
 * @param status          HELD until the line is shipped (COMMITTED) or given back (RELEASED)
 * @param stockOwnerOrgId owner of the reserved stock (AfroTransact Retail for 1P, the seller for FBA)
 */
public record ReservationLine(int lineNo, String sku, String nodeId, int qty, List<LotAllocation> allocations,
                              String group, LineStatus status, String stockOwnerOrgId) {

    /** Lifecycle of one line inside a reservation. */
    public enum LineStatus {
        HELD,
        COMMITTED,
        RELEASED
    }

    public ReservationLine {
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
        status = status == null ? LineStatus.HELD : status;
        group = group == null || group.isBlank() ? null : group;
    }

    /** A held line without group or known owner (callers that predate groups). */
    public ReservationLine(int lineNo, String sku, String nodeId, int qty, List<LotAllocation> allocations) {
        this(lineNo, sku, nodeId, qty, allocations, null, LineStatus.HELD, null);
    }

    public long costMinor() {
        return allocations.stream().mapToLong(LotAllocation::costMinor).sum();
    }

    public boolean isHeld() {
        return status == LineStatus.HELD;
    }

    /** True when the line belongs to one of the groups. */
    public boolean inAny(Set<String> groups) {
        return group != null && groups.contains(group);
    }

    public ReservationLine withStatus(LineStatus next) {
        return new ReservationLine(lineNo, sku, nodeId, qty, allocations, group, next, stockOwnerOrgId);
    }
}
