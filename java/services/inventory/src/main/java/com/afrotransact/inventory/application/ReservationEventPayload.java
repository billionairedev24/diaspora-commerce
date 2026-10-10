package com.afrotransact.inventory.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.Reservation;

/** Payload of {@code inventory.reservation.*} events. COGS is set on {@code committed}. */
public record ReservationEventPayload(UUID reservationId, String orderId, String status, Instant expiresAt,
                                      List<Line> lines, List<CommitResult.Money> cogs, String reason) {

    /** A reserved line and its lot allocations. */
    public record Line(int lineNo, String sku, String nodeId, int qty, List<LotAllocation> allocations) {
    }

    static ReservationEventPayload of(Reservation r, List<CommitResult.Money> cogs, String reason) {
        return new ReservationEventPayload(r.id(), r.orderId(), r.status().name(), r.expiresAt(),
                r.lines().stream().map(l -> new Line(l.lineNo(), l.sku(), l.nodeId(), l.qty(), l.allocations()))
                        .toList(),
                cogs, reason);
    }
}
