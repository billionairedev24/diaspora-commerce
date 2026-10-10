package com.afrotransact.inventory.domain;

/** The four quantity buckets of a position at one moment. */
public record StockLevels(int onHand, int reserved, int damaged, int inbound) {

    public static final StockLevels ZERO = new StockLevels(0, 0, 0, 0);

    public int available() {
        return onHand - reserved;
    }

    public StockLevels minus(StockLevels other) {
        return new StockLevels(onHand - other.onHand, reserved - other.reserved, damaged - other.damaged,
                inbound - other.inbound);
    }

    public StockLevels plus(StockLevels other) {
        return new StockLevels(onHand + other.onHand, reserved + other.reserved, damaged + other.damaged,
                inbound + other.inbound);
    }

    /** Sum of absolute differences across buckets. */
    public long distance(StockLevels other) {
        StockLevels d = minus(other);
        return (long) Math.abs(d.onHand) + Math.abs(d.reserved) + Math.abs(d.damaged) + Math.abs(d.inbound);
    }
}
