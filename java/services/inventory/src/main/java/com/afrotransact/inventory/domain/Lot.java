package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * A batch of one SKU at one node (unique per sku, lotNo, nodeId) with its dates, bin and unit landed cost.
 * Only SELLABLE lots can be reserved; reserved units must be released or shipped before a lot changes status.
 */
public record Lot(UUID id, String sku, String lotNo, String nodeId, String ownerOrg, String bin, int qtyOnHand,
                  int qtyReserved, LocalDate mfgDate, LocalDate expDate, Instant receivedAt,
                  long unitLandedCostMinor, String currency, LotStatus status, String reason,
                  MarkdownFlag markdownFlag, String receiptRef, long version) {

    /** Suffix of the separate quarantine lot that holds units damaged on receipt or on return. */
    public static final String DAMAGED_SUFFIX = "-DMG";

    public Lot {
        if (qtyOnHand < 0 || qtyReserved < 0 || qtyReserved > qtyOnHand) {
            throw new InventoryRuleViolation("inventory.below-reserved", "Lot " + lotNo + " cannot hold fewer units ("
                    + qtyOnHand + ") than are reserved (" + qtyReserved + ")",
                    Map.of("qty", "Lot has " + qtyReserved + " units reserved"));
        }
        if (qtyReserved > 0 && status != LotStatus.SELLABLE) {
            throw new InventoryStateConflict("inventory.lot-reserved", "Lot " + lotNo + " has " + qtyReserved
                    + " reserved units; release or ship them first");
        }
        markdownFlag = markdownFlag == null ? MarkdownFlag.NONE : markdownFlag;
    }

    /** Units that can still be allocated. */
    public int available() {
        return status == LotStatus.SELLABLE ? qtyOnHand - qtyReserved : 0;
    }

    /** True when the lot is sellable and does not expire within {@code minRemainingDays} of {@code today}. */
    public boolean allocatableOn(LocalDate today, int minRemainingDays) {
        return available() > 0 && (expDate == null || !expDate.isBefore(today.plusDays(minRemainingDays)));
    }

    public boolean expiredOn(LocalDate today) {
        return expDate != null && !expDate.isAfter(today);
    }

    public boolean isDamagedLot() {
        return lotNo.endsWith(DAMAGED_SUFFIX);
    }

    public Lot reserve(int qty) {
        if (qty <= 0 || qty > available()) {
            throw new InventoryStateConflict("inventory.lot-unavailable", "Lot " + lotNo + " has only " + available()
                    + " units available");
        }
        return withQty(qtyOnHand, qtyReserved + qty);
    }

    public Lot release(int qty) {
        return withQty(qtyOnHand, qtyReserved - qty);
    }

    public Lot ship(int qty) {
        return withQty(qtyOnHand - qty, qtyReserved - qty);
    }

    /** Adds units (restock, transfer in, top-up of a damaged lot). */
    public Lot add(int qty) {
        return withQty(qtyOnHand + qty, qtyReserved);
    }

    /** Removes unreserved units (transfer out, partial write-off). */
    public Lot remove(int qty) {
        return withQty(qtyOnHand - qty, qtyReserved);
    }

    /** Cycle count: sets the counted quantity. Reserved units must still be covered. */
    public Lot countedAs(int counted, String why) {
        Lot next = withQty(counted, qtyReserved);
        return next.withStatus(status, why);
    }

    public Lot quarantine(String why) {
        requireStatus(LotStatus.SELLABLE, "quarantine");
        requireUnreserved("quarantine");
        return withStatus(LotStatus.QUARANTINE, why);
    }

    public Lot releaseQuarantine(String why, LocalDate today) {
        if (status != LotStatus.QUARANTINE && status != LotStatus.DAMAGED) {
            throw new InventoryStateConflict("inventory.invalid-lot-state", "Lot " + lotNo + " is " + status
                    + "; only quarantined or damaged lots can be released");
        }
        if (expiredOn(today)) {
            throw new InventoryRuleViolation("inventory.lot-expired", "Lot " + lotNo + " expired on " + expDate
                    + " and cannot be released for sale");
        }
        return withStatus(LotStatus.SELLABLE, why);
    }

    public Lot expire() {
        if (!status.held() && status != LotStatus.SELLABLE) {
            throw new InventoryStateConflict("inventory.invalid-lot-state", "Lot " + lotNo + " is " + status);
        }
        requireUnreserved("expire");
        return withStatus(LotStatus.EXPIRED, "Expired on " + expDate);
    }

    /** Writes off {@code qty} units; the lot becomes WRITTEN_OFF when nothing is left. */
    public Lot writeOff(int qty, String why) {
        if (status == LotStatus.WRITTEN_OFF) {
            throw new InventoryStateConflict("inventory.invalid-lot-state", "Lot " + lotNo + " is already written off");
        }
        if (qty <= 0 || qty > qtyOnHand - qtyReserved) {
            throw new InventoryRuleViolation("inventory.below-reserved", "Lot " + lotNo + " has "
                    + (qtyOnHand - qtyReserved) + " unreserved units; cannot write off " + qty,
                    Map.of("qty", "At most " + (qtyOnHand - qtyReserved)));
        }
        Lot remaining = withQty(qtyOnHand - qty, qtyReserved);
        return remaining.qtyOnHand == 0 ? remaining.withStatus(LotStatus.WRITTEN_OFF, why) : remaining.withStatus(status, why);
    }

    public Lot withMarkdown(MarkdownFlag flag) {
        return new Lot(id, sku, lotNo, nodeId, ownerOrg, bin, qtyOnHand, qtyReserved, mfgDate, expDate, receivedAt,
                unitLandedCostMinor, currency, status, reason, flag, receiptRef, version);
    }

    private void requireStatus(LotStatus expected, String action) {
        if (status != expected) {
            throw new InventoryStateConflict("inventory.invalid-lot-state", "Cannot " + action + " lot " + lotNo
                    + " in status " + status);
        }
    }

    private void requireUnreserved(String action) {
        if (qtyReserved > 0) {
            throw new InventoryStateConflict("inventory.lot-reserved", "Cannot " + action + " lot " + lotNo + ": "
                    + qtyReserved + " units are reserved for open orders");
        }
    }

    private Lot withQty(int onHand, int reservedQty) {
        return new Lot(id, sku, lotNo, nodeId, ownerOrg, bin, onHand, reservedQty, mfgDate, expDate, receivedAt,
                unitLandedCostMinor, currency, status, reason, markdownFlag, receiptRef, version);
    }

    private Lot withStatus(LotStatus next, String why) {
        return new Lot(id, sku, lotNo, nodeId, ownerOrg, bin, qtyOnHand, qtyReserved, mfgDate, expDate, receivedAt,
                unitLandedCostMinor, currency, next, why, markdownFlag, receiptRef, version);
    }
}
