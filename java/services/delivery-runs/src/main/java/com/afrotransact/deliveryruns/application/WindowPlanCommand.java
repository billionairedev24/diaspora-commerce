package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKey;

/**
 * What the window wizard knows while staff fill it in. The location is the one named, else the active one matching the
 * first city target.
 *
 * @param locationId     the location the closing run is planned for (null = matched by city)
 * @param targets        where the window sells
 * @param door           door delivery offered
 * @param pickup         pickup point offered
 * @param doorFeeMinor   the window's own door fee (null = the fee rule for the location's distance)
 * @param pickupFeeMinor the window's own pickup fee (null = the pickup rule)
 * @param minOrders      the minimum being weighed (null or 0 = none)
 * @param expectedOrders the orders to show a P&amp;L for (null = the minimum, at least 1)
 * @param capacityOrders the most orders the run can carry, to show the margin at capacity (null = the location's
 *                       default capacity; none when that is not limited)
 */
public record WindowPlanCommand(UUID locationId, List<AreaKey> targets, boolean door, boolean pickup,
                                Long doorFeeMinor, Long pickupFeeMinor, Integer minOrders, Integer expectedOrders,
                                Integer capacityOrders) {

    /** A plan without a capacity of its own. */
    public WindowPlanCommand(UUID locationId, List<AreaKey> targets, boolean door, boolean pickup,
                             Long doorFeeMinor, Long pickupFeeMinor, Integer minOrders, Integer expectedOrders) {
        this(locationId, targets, door, pickup, doorFeeMinor, pickupFeeMinor, minOrders, expectedOrders, null);
    }

    public WindowPlanCommand {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
