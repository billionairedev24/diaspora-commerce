package com.afrotransact.retail.domain;

import java.util.EnumSet;
import java.util.Set;

/** Supplier lifecycle (design: Onboarding, Approved, On watch; plus Inactive). */
public enum SupplierStatus {
    ONBOARDING,
    APPROVED,
    ON_WATCH,
    INACTIVE;

    /** Statuses this one may move to. */
    public Set<SupplierStatus> next() {
        return switch (this) {
            case ONBOARDING -> EnumSet.of(APPROVED, INACTIVE);
            case APPROVED -> EnumSet.of(ON_WATCH, INACTIVE);
            case ON_WATCH -> EnumSet.of(APPROVED, INACTIVE);
            case INACTIVE -> EnumSet.of(APPROVED);
        };
    }

    /** Only approved suppliers (including those on watch) can receive new purchase orders. */
    public boolean canReceivePurchaseOrders() {
        return this == APPROVED || this == ON_WATCH;
    }
}
