package com.afrotransact.inventory.adapter.in.module;

import com.afrotransact.inventory.api.ReservationApi.ReservationInfo;
import com.afrotransact.inventory.api.ReservationApi.ReservedLine;
import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationLine;

/** The published view of a reservation: only what other modules read (SKU, node, quantity, group, lots). */
final class ReservationViews {

    private ReservationViews() {
    }

    static ReservationInfo of(Reservation r, boolean replayed) {
        return new ReservationInfo(r.orderId(), r.status().name(), replayed,
                r.lines().stream().map(ReservationViews::line).toList());
    }

    private static ReservedLine line(ReservationLine l) {
        return new ReservedLine(l.sku(), l.nodeId(), l.qty(), l.group(),
                l.allocations().stream().map(LotAllocation::lotId).map(String::valueOf).toList());
    }
}
