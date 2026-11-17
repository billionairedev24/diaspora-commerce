package com.afrotransact.orders.domain;

import java.util.function.Supplier;

/**
 * Launch shipping (listing rules D16 to D18): AfroTransact sets all shipping. Delivery by AfroTransact (and
 * AfroTransact collecting from a seller's store) costs the staff-set flat fee, free above the optional threshold; pickup
 * and collecting from the store cost 0. There is no express service. A seller's own shipping exists only behind the staff
 * switch ({@link ShippingSettings#sellerSelfShippingEnabled()}, off by default) and is the seller's own flat charge, kept
 * by the seller (see {@code SellerSelfShippingService}).
 *
 * <p>The fee is read from the shipping settings for every charge, so the estimate the shopper sees (gateway-bff reads the
 * same record) and the amount charged here always come from one source.
 */
public final class DefaultShippingPolicy implements ShippingPolicy {

    public static final String STANDARD = "standard";
    public static final String LOCAL = "local";
    public static final String PICKUP = "pickup";

    private final Supplier<ShippingSettings> settings;
    private final Rates rates;

    public DefaultShippingPolicy(Supplier<ShippingSettings> settings, Rates rates) {
        this.settings = settings;
        this.rates = rates;
    }

    /**
     * Fallback estimates in business days and the carrier fee of seller-shipped packages (only used while the staff
     * switch is on).
     */
    public record Rates(long carrierStandardMinor, long freeCarrierAboveMinor, int standardDays, int deliveryDays,
                        int pickupDays) {

        public static Rates defaults() {
            return new Rates(599, 3500, 5, 1, 1);
        }
    }

    @Override
    public boolean selfShippingEnabled() {
        return settings.get().sellerSelfShippingEnabled();
    }

    @Override
    public long fee(DeliveryMethod method, String serviceLevel, long itemsMinor) {
        return switch (method) {
            case PICKUP, SELLER_SELF_SHIPS -> 0; // the seller's own charge is not AfroTransact's to price
            case AFROTRANSACT_DELIVERY, SELLER_SHIPS -> settings.get().deliveryFee(itemsMinor);
            case CARRIER -> itemsMinor >= rates.freeCarrierAboveMinor() ? 0 : rates.carrierStandardMinor();
        };
    }

    @Override
    public int estimatedDays(DeliveryMethod method, String serviceLevel) {
        return switch (method) {
            case PICKUP -> rates.pickupDays();
            case AFROTRANSACT_DELIVERY, SELLER_SHIPS, SELLER_SELF_SHIPS -> rates.deliveryDays();
            case CARRIER -> rates.standardDays();
        };
    }

    @Override
    public String defaultServiceLevel(DeliveryMethod method) {
        return switch (method) {
            case PICKUP -> PICKUP;
            case AFROTRANSACT_DELIVERY, SELLER_SHIPS, SELLER_SELF_SHIPS -> LOCAL;
            case CARRIER -> STANDARD;
        };
    }
}
