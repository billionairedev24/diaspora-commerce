package com.afrotransact.inventory.domain;

import java.util.List;
import java.util.Map;

/**
 * Stock of one SKU at one node. {@code available = onHand − reserved} is what the storefront sells.
 * Every change returns a new position; persistence bumps {@code version} once per transaction.
 *
 * @param onHand   sellable units physically present (Σ SELLABLE lots)
 * @param reserved units held by HELD reservations (never more than onHand)
 * @param inbound  units expected on advance shipping notices
 * @param damaged  units present but blocked from sale (quarantine, damaged, expired lots)
 */
public record StockPosition(String sku, String nodeId, String ownerOrg, int onHand, int reserved, int inbound,
                            int damaged, long version) {

    public StockPosition {
        if (onHand < 0 || reserved < 0 || inbound < 0 || damaged < 0) {
            throw new InventoryRuleViolation("inventory.negative-stock", "Stock of " + sku + " at " + nodeId
                    + " cannot go negative");
        }
        if (reserved > onHand) {
            throw new InventoryRuleViolation("inventory.below-reserved", "On hand of " + sku + " at " + nodeId
                    + " cannot go below the " + reserved + " units already reserved",
                    Map.of("qty", "Would leave " + onHand + " on hand for " + reserved + " reserved"));
        }
    }

    public static StockPosition empty(String sku, String nodeId, String ownerOrg) {
        return new StockPosition(sku, nodeId, ownerOrg, 0, 0, 0, 0, 0);
    }

    public int available() {
        return onHand - reserved;
    }

    public StockLevels levels() {
        return new StockLevels(onHand, reserved, damaged, inbound);
    }

    /** Holds {@code qty} sellable units for a checkout. */
    public StockPosition reserve(int qty) {
        positive(qty);
        if (qty > available()) {
            throw new InsufficientStockException(List.of(new Shortfall(sku, nodeId, qty, available())));
        }
        return with(onHand, reserved + qty, inbound, damaged);
    }

    public StockPosition release(int qty) {
        positive(qty);
        if (qty > reserved) {
            throw new InventoryStateConflict("inventory.release-exceeds-reserved", "Cannot release " + qty
                    + " units of " + sku + " at " + nodeId + "; only " + reserved + " reserved");
        }
        return with(onHand, reserved - qty, inbound, damaged);
    }

    /** Shipment of reserved units: on hand and reserved both go down. */
    public StockPosition ship(int qty) {
        positive(qty);
        if (qty > reserved) {
            throw new InventoryStateConflict("inventory.ship-exceeds-reserved", "Cannot ship " + qty + " units of "
                    + sku + " at " + nodeId + "; only " + reserved + " reserved");
        }
        return with(onHand - qty, reserved - qty, inbound, damaged);
    }

    /**
     * Changes sellable on-hand by {@code delta}. A negative delta can never take on-hand below what is reserved
     * (the adjustment guard), so cycle counts and write-offs cannot break promised orders.
     */
    public StockPosition adjustSellable(int delta) {
        if (delta < 0 && onHand + delta < reserved) {
            throw new InventoryRuleViolation("inventory.below-reserved", "Adjusting " + sku + " at " + nodeId + " by "
                    + delta + " would leave " + (onHand + delta) + " on hand, below the " + reserved
                    + " units reserved", Map.of("qty", "At most " + available() + " units can be removed"));
        }
        return with(onHand + delta, reserved, inbound, damaged);
    }

    /** Changes held (blocked) units by {@code delta}. */
    public StockPosition adjustHeld(int delta) {
        if (damaged + delta < 0) {
            throw new InventoryRuleViolation("inventory.negative-stock", "Held stock of " + sku + " at " + nodeId
                    + " cannot go negative");
        }
        return with(onHand, reserved, inbound, damaged + delta);
    }

    /** Moves sellable units into the held bucket (quarantine, expiry). Guarded like any sellable decrease. */
    public StockPosition hold(int qty) {
        positive(qty);
        return adjustSellable(-qty).adjustHeld(qty);
    }

    /** Moves held units back to sellable (quarantine released after inspection). */
    public StockPosition unhold(int qty) {
        positive(qty);
        return adjustHeld(-qty).adjustSellable(qty);
    }

    public StockPosition adjustInbound(int delta) {
        return with(onHand, reserved, Math.max(0, inbound + delta), damaged);
    }

    private StockPosition with(int newOnHand, int newReserved, int newInbound, int newDamaged) {
        return new StockPosition(sku, nodeId, ownerOrg, newOnHand, newReserved, newInbound, newDamaged, version);
    }

    private static void positive(int qty) {
        if (qty <= 0) {
            throw new InventoryRuleViolation("inventory.invalid-quantity", "Quantity must be positive",
                    Map.of("qty", "Must be at least 1"));
        }
    }
}
