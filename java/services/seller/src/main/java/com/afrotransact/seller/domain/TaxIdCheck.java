package com.afrotransact.seller.domain;

/** A validated tax ID: its kind and the dashed format ({@code 123-45-6789} / {@code 12-3456789}). */
public record TaxIdCheck(TaxIdType type, String formatted) {

    public String last4() {
        return TaxIds.last4(formatted);
    }
}
