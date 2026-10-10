package com.afrotransact.inventory.domain;

/** Inventory age since receipt at the FC (FBA ageing, S067), in days. */
public enum AgeBucket {
    D0_90(0, 90),
    D91_180(91, 180),
    D181_270(181, 270),
    D271_365(271, 365),
    D365_PLUS(366, Integer.MAX_VALUE);

    private final int fromDays;
    private final int toDays;

    AgeBucket(int fromDays, int toDays) {
        this.fromDays = fromDays;
        this.toDays = toDays;
    }

    public static AgeBucket of(long days) {
        for (AgeBucket b : values()) {
            if (days <= b.toDays) {
                return b;
            }
        }
        return D365_PLUS;
    }

    public int fromDays() {
        return fromDays;
    }

    public int toDays() {
        return toDays;
    }
}
