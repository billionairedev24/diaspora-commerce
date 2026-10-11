package com.afrotransact.inventory.domain;

/**
 * One SKU of an inbound shipment.
 *
 * @param sku             inventory SKU the units are stocked under (FBA: the offer's inventory SKU)
 * @param atin            catalog variant ATIN (unit labels, Fulfilled-by-AfroTransact switch); null for 1P ASNs
 * @param unitLabel       the stock owner's AT-FN unit label code from catalog (ATF + 7), null until issued
 * @param expectedQty     units announced (0 for units that arrived without being announced)
 * @param receivedGood    sellable units received so far
 * @param receivedDamaged damaged units received so far (quarantined as -DMG lots)
 * @param inboundOpen     expected units still counted in the position's inbound bucket
 * @param batch           case pack and the announced batch (lot, made and expiry dates); never null
 */
public record InboundLine(int lineNo, String sku, String atin, String unitLabel, int expectedQty, int receivedGood,
                          int receivedDamaged, int inboundOpen, ExpectedBatch batch) {

    public InboundLine {
        if (expectedQty < 0 || receivedGood < 0 || receivedDamaged < 0 || inboundOpen < 0) {
            throw new InventoryRuleViolation("inventory.invalid-quantity", "Inbound quantities cannot be negative");
        }
        batch = batch == null ? ExpectedBatch.NONE : batch;
    }

    /** A line without an announced batch. */
    public InboundLine(int lineNo, String sku, String atin, String unitLabel, int expectedQty, int receivedGood,
                       int receivedDamaged, int inboundOpen) {
        this(lineNo, sku, atin, unitLabel, expectedQty, receivedGood, receivedDamaged, inboundOpen,
                ExpectedBatch.NONE);
    }

    public int received() {
        return receivedGood + receivedDamaged;
    }

    /** Units announced but not (yet) received. */
    public int shortQty() {
        return Math.max(0, expectedQty - received());
    }

    /** Units received beyond what was announced. */
    public int overQty() {
        return Math.max(0, received() - expectedQty);
    }

    public InboundLine withUnitLabel(String code) {
        return new InboundLine(lineNo, sku, atin, code, expectedQty, receivedGood, receivedDamaged, inboundOpen, batch);
    }

    public InboundLine withInboundOpen(int open) {
        return new InboundLine(lineNo, sku, atin, unitLabel, expectedQty, receivedGood, receivedDamaged, open, batch);
    }

    /** Records received units; returns the line with {@code consumed} expected units taken off its inbound. */
    public InboundLine receive(int good, int damaged, int consumed) {
        return new InboundLine(lineNo, sku, atin, unitLabel, expectedQty, receivedGood + good,
                receivedDamaged + damaged, inboundOpen - consumed, batch);
    }
}
