package com.afrotransact.orders.domain;

/**
 * Which pickup points a package may be collected from: platform-fulfilled packages (AfroTransact Retail and Fulfilled by
 * AfroTransact) at an AfroTransact counter or locker that staff configured; a seller's own packages (shipped or
 * delivered from the store) at one of that seller's pickup locations (its store address or another place it set up).
 */
public final class PickupEligibility {

    private PickupEligibility() {
    }

    /**
     * Why the point can't be used for the package, or null when it can.
     *
     * @param kind       PLATFORM_COUNTER | LOCKER | SELLER (null when the point is unknown)
     * @param ownerOrg   the point's owner organisation
     * @param active     the point takes orders
     * @param fulfilment the package's fulfilment
     * @param sellerOrg  the package's seller organisation
     */
    public static String refusal(String kind, String ownerOrg, boolean active, FulfilmentType fulfilment,
                                 String sellerOrg) {
        if (kind == null || kind.isBlank()) {
            return "This pickup location doesn't exist";
        }
        if (!active) {
            return "This pickup location isn't taking orders right now";
        }
        boolean sellerPoint = "SELLER".equalsIgnoreCase(kind);
        if (fulfilment == FulfilmentType.SELLER_FULFILLED) {
            return sellerPoint && sellerOrg != null && sellerOrg.equals(ownerOrg) ? null
                    : "Choose one of the seller's own pickup locations";
        }
        return sellerPoint ? "Choose an AfroTransact pickup location" : null;
    }
}
