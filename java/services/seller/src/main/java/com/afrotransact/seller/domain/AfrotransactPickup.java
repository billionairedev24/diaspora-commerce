package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * AfroTransact Delivery from this ship-from location (S255): the seller packs, the nearest AfroTransact driver
 * collects here during the pickup hours and delivers.
 *
 * @param zoneName  the active DELIVERY zone covering (or nearest to) the location when pickup was enabled
 * @param enabledAt when pickup was (last) enabled
 */
public record AfrotransactPickup(boolean enabled, List<PickupWindow> hours, String zoneName, Instant enabledAt) {

    /** Most pickup windows per location (two per day). */
    public static final int MAX_WINDOWS = 14;

    public static final AfrotransactPickup DISABLED = new AfrotransactPickup(false, List.of(), null, null);

    public AfrotransactPickup {
        List<PickupWindow> sorted = new ArrayList<>(hours == null ? List.of() : hours);
        sorted.sort(Comparator.comparing(PickupWindow::day).thenComparing(PickupWindow::opens));
        if (sorted.size() > MAX_WINDOWS) {
            throw new SellerRuleViolation("seller.invalid-pickup-hours", "At most " + MAX_WINDOWS
                    + " pickup windows", Map.of("hours", "At most " + MAX_WINDOWS));
        }
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).overlaps(sorted.get(i))) {
                throw new SellerRuleViolation("seller.invalid-pickup-hours", "Pickup windows overlap on "
                        + sorted.get(i).day(), Map.of("hours", "Windows must not overlap"));
            }
        }
        if (enabled && sorted.isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-pickup-hours", "Give at least one pickup window",
                    Map.of("hours", "Required to enable AfroTransact pickup"));
        }
        hours = List.copyOf(sorted);
    }
}
