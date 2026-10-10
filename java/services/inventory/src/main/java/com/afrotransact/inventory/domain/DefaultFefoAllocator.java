package com.afrotransact.inventory.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * FEFO: earliest expiry first (lots without expiry last), then oldest receipt, then lot number for a stable order.
 * Lots that are not sellable, fully reserved, or expire within the minimum remaining days are skipped.
 */
public class DefaultFefoAllocator implements FefoAllocator {

    public static final Comparator<Lot> FEFO_ORDER = Comparator
            .comparing(Lot::expDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Lot::receivedAt)
            .thenComparing(Lot::lotNo);

    @Override
    public Result allocate(List<Lot> lots, int qty, LocalDate today, int minRemainingDays) {
        if (qty <= 0) {
            throw new InventoryRuleViolation("inventory.invalid-quantity", "Quantity must be positive");
        }
        List<Lot> eligible = lots.stream()
                .filter(l -> l.allocatableOn(today, minRemainingDays))
                .sorted(FEFO_ORDER)
                .toList();
        int allocatable = eligible.stream().mapToInt(Lot::available).sum();
        List<LotAllocation> picks = new ArrayList<>();
        int remaining = qty;
        for (Lot lot : eligible) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(remaining, lot.available());
            picks.add(new LotAllocation(lot.id(), lot.lotNo(), take, lot.expDate(), lot.unitLandedCostMinor(),
                    lot.currency()));
            remaining -= take;
        }
        return new Result(picks, qty, allocatable);
    }
}
