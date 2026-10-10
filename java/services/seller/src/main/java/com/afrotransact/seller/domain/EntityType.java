package com.afrotransact.seller.domain;

import java.util.Locale;

/** Legal form of the business; decides which business details, tax ID and owners are required. */
public enum EntityType {
    SOLE_PROPRIETOR,
    SINGLE_MEMBER_LLC,
    MULTI_MEMBER_LLC,
    CORPORATION,
    PARTNERSHIP,
    NONPROFIT;

    /** Parses {@code sole_proprietor}, {@code SOLE_PROPRIETOR} or {@code sole-proprietor}. */
    public static EntityType parse(String raw) {
        return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isIndividual() {
        return this == SOLE_PROPRIETOR;
    }

    /** Owners step is hidden for sole proprietors. */
    public boolean needsOwners() {
        return this != SOLE_PROPRIETOR;
    }

    /** Multi-member LLCs and partnerships need two or more people. */
    public int minimumOwners() {
        return this == MULTI_MEMBER_LLC || this == PARTNERSHIP ? 2 : 1;
    }

    public TaxIdType taxIdType() {
        return this == SOLE_PROPRIETOR ? TaxIdType.SSN : TaxIdType.EIN;
    }
}
