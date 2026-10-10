package com.afrotransact.retail.domain;

/** Storage requirement of an item; chilled items must be put away in K- bins. */
public enum StorageType {
    AMBIENT,
    CHILLED,
    FROZEN,
    HAZMAT;

    /** Bin prefix expected at put-away, or null when any ambient bin will do. */
    public String binPrefix() {
        return switch (this) {
            case CHILLED, FROZEN -> "K-";
            case HAZMAT -> "H-";
            case AMBIENT -> null;
        };
    }
}
