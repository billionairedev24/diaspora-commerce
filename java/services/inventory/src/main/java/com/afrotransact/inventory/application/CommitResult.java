package com.afrotransact.inventory.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationLine;

/**
 * Shipment of a reservation with its cost of goods sold at each lot's unit landed cost (FIFO by lot).
 *
 * @param cogs  COGS per currency (normally one)
 * @param lines COGS per order line
 */
public record CommitResult(Reservation reservation, List<Money> cogs, List<LineCost> lines, boolean replayed) {

    /** An amount in minor units. */
    public record Money(String currency, long amountMinor) {
    }

    /** COGS of one order line. */
    public record LineCost(int lineNo, String sku, String nodeId, int qty, List<Money> cogs, String group,
                           String stockOwnerOrgId) {
    }

    /** COGS of every line the reservation shipped. */
    static CommitResult of(Reservation r, boolean replayed) {
        return ofLines(r, r.committedLines(), replayed);
    }

    /** COGS of the given (shipped) lines, e.g. the lines of the groups committed by one call. */
    static CommitResult ofLines(Reservation r, List<ReservationLine> shipped, boolean replayed) {
        List<LineCost> lines = new ArrayList<>();
        Map<String, Long> total = new TreeMap<>();
        for (ReservationLine line : shipped) {
            Map<String, Long> perLine = new TreeMap<>();
            for (LotAllocation a : line.allocations()) {
                perLine.merge(a.currency(), a.costMinor(), Long::sum);
                total.merge(a.currency(), a.costMinor(), Long::sum);
            }
            lines.add(new LineCost(line.lineNo(), line.sku(), line.nodeId(), line.qty(), money(perLine), line.group(),
                    line.stockOwnerOrgId()));
        }
        return new CommitResult(r, money(total), lines, replayed);
    }

    private static List<Money> money(Map<String, Long> amounts) {
        return amounts.entrySet().stream().map(e -> new Money(e.getKey(), e.getValue())).toList();
    }
}
