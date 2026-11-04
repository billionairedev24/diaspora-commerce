package com.afrotransact.inventory.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationLine;

/**
 * Payload of {@code inventory.reservation.*} events. COGS is set on {@code committed} / {@code group_committed}:
 * in US cents ({@code cogsUsdMinor}, what finance posts) and per currency the lots were paid in.
 *
 * @param status the reservation's status after the change (HELD while other groups are still held)
 * @param groups the groups a {@code group_committed} / {@code group_released} event is about; null for whole-order
 *               events
 * @param lines  every line for whole-order events, the lines of {@code groups} for group events
 */
public record ReservationEventPayload(UUID reservationId, String orderId, String status, Instant expiresAt,
                                      List<Line> lines, List<CommitResult.Money> cogs, long cogsUsdMinor,
                                      String reason, List<String> groups) {

    /**
     * A reserved line and its lot allocations.
     *
     * @param stockOwnerOrgId owner of the stock (AfroTransact Retail for 1P, the FBA seller otherwise)
     */
    public record Line(int lineNo, String sku, String nodeId, int qty, List<LotAllocation> allocations,
                       String group, String status, String stockOwnerOrgId) {

        static Line of(ReservationLine l) {
            return new Line(l.lineNo(), l.sku(), l.nodeId(), l.qty(), l.allocations(), l.group(), l.status().name(),
                    l.stockOwnerOrgId());
        }
    }

    static ReservationEventPayload of(Reservation r, List<CommitResult.Money> cogs, long cogsUsdMinor,
                                      String reason) {
        return new ReservationEventPayload(r.id(), r.orderId(), r.status().name(), r.expiresAt(),
                r.lines().stream().map(Line::of).toList(), cogs, cogsUsdMinor, reason, null);
    }

    static ReservationEventPayload ofGroups(Reservation r, List<ReservationLine> lines, Set<String> groups,
                                            List<CommitResult.Money> cogs, long cogsUsdMinor, String reason) {
        return new ReservationEventPayload(r.id(), r.orderId(), r.status().name(), r.expiresAt(),
                lines.stream().map(Line::of).toList(), cogs, cogsUsdMinor, reason, List.copyOf(groups));
    }
}
