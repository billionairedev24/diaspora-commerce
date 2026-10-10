package com.afrotransact.deliveryruns.domain;

/**
 * Run capacity: orders and/or packages; a null limit means unlimited on that dimension.
 *
 * @param maxOrders   most orders the run takes
 * @param maxPackages most packages (parcels, bags, cool boxes) the vehicle takes
 */
public record Capacity(Integer maxOrders, Integer maxPackages) {

    public static final Capacity UNLIMITED = new Capacity(null, null);

    public Capacity {
        if ((maxOrders != null && maxOrders < 1) || (maxPackages != null && maxPackages < 1)) {
            throw new RuleViolation("delivery-runs.invalid-capacity", "Capacity limits must be at least 1");
        }
    }

    /** Whether one more order of {@code packages} fits on top of what is already reserved. */
    public boolean fits(int reservedOrders, int reservedPackages, int packages) {
        boolean ordersOk = maxOrders == null || reservedOrders + 1 <= maxOrders;
        boolean packagesOk = maxPackages == null || reservedPackages + packages <= maxPackages;
        return ordersOk && packagesOk;
    }

    /** Orders still available, or null when orders are not limited. */
    public Integer ordersLeft(int reservedOrders) {
        return maxOrders == null ? null : Math.max(0, maxOrders - reservedOrders);
    }

    /** Whether these limits still hold what is already reserved (staff cannot shrink below it). */
    public boolean holds(int reservedOrders, int reservedPackages) {
        return (maxOrders == null || reservedOrders <= maxOrders)
                && (maxPackages == null || reservedPackages <= maxPackages);
    }
}
