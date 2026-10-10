package com.afrotransact.inventory.domain;

import java.time.LocalDate;
import java.util.List;

/** Chooses which lots fill a quantity: first-expiry-first-out. */
public interface FefoAllocator {

    /**
     * Allocates {@code qty} units from {@code lots} (all of one SKU at one node).
     *
     * @param today            node-local date
     * @param minRemainingDays lots expiring before {@code today + minRemainingDays} are skipped
     */
    Result allocate(List<Lot> lots, int qty, LocalDate today, int minRemainingDays);

    /**
     * Allocation outcome.
     *
     * @param picks       lots and quantities in pick order (complete only when {@link #satisfied()})
     * @param requested   units asked for
     * @param allocatable units the eligible lots could provide in total
     */
    record Result(List<LotAllocation> picks, int requested, int allocatable) {

        public Result {
            picks = List.copyOf(picks);
        }

        public boolean satisfied() {
            return allocatable >= requested;
        }
    }
}
