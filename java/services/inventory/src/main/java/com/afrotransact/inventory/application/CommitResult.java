package com.afrotransact.inventory.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationLine;

/**
 * Shipment of a reservation with its cost of goods sold at each lot's unit landed cost (FIFO by lot). COGS is valued in
 * US dollars (what margin uses); the amounts in the currencies the lots were paid in stay alongside.
 *
 * @param cogs         COGS per currency the lots were paid in
 * @param cogsUsdMinor COGS in US cents
 * @param lines        COGS per order line
 */
public record CommitResult(Reservation reservation, List<Money> cogs, long cogsUsdMinor, List<LineCost> lines,
                           boolean replayed) {

    /** An amount in minor units. */
    public record Money(String currency, long amountMinor) {
    }

    /** COGS of one order line, in the lots' currencies and in US cents. */
    public record LineCost(int lineNo, String sku, String nodeId, int qty, List<Money> cogs, long cogsUsdMinor,
                           String group, String stockOwnerOrgId) {
    }

    /** COGS of every line the reservation shipped. */
    static CommitResult of(Reservation r, boolean replayed) {
        return ofLines(r, r.committedLines(), replayed);
    }

    /** COGS of the given (shipped) lines, e.g. the lines of the groups committed by one call. */
    static CommitResult ofLines(Reservation r, List<ReservationLine> shipped, boolean replayed) {
        List<LineCost> lines = new ArrayList<>();
        Map<String, Long> total = new TreeMap<>();
        long totalUsd = 0;
        for (ReservationLine line : shipped) {
            Map<String, Long> perLine = new TreeMap<>();
            long lineUsd = 0;
            for (LotAllocation a : line.allocations()) {
                perLine.merge(a.currency(), a.costMinor(), Long::sum);
                total.merge(a.currency(), a.costMinor(), Long::sum);
                lineUsd = Math.addExact(lineUsd, a.costUsdMinor());
            }
            totalUsd = Math.addExact(totalUsd, lineUsd);
            lines.add(new LineCost(line.lineNo(), line.sku(), line.nodeId(), line.qty(), money(perLine), lineUsd,
                    line.group(), line.stockOwnerOrgId()));
        }
        return new CommitResult(r, money(total), totalUsd, lines, replayed);
    }

    private static List<Money> money(Map<String, Long> amounts) {
        return amounts.entrySet().stream().map(e -> new Money(e.getKey(), e.getValue())).toList();
    }
}
