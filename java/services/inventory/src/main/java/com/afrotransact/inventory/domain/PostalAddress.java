package com.afrotransact.inventory.domain;

/** A postal address (ship-from of an inbound shipment, address of a fulfilment centre). */
public record PostalAddress(String name, String line1, String line2, String city, String region, String postalCode,
                            String country, String phone) {

    /** True when a carrier label can be bought for it. */
    public boolean complete() {
        return notBlank(line1) && notBlank(city) && notBlank(postalCode) && notBlank(country);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
