package com.afrotransact.orders.domain;

import java.util.Locale;

/**
 * Flat fees: carrier standard (free above a threshold), carrier express, AfroTransact Delivery (free above a
 * threshold) and free pickup.
 */
public final class DefaultShippingPolicy implements ShippingPolicy {

    public static final String STANDARD = "standard";
    public static final String EXPRESS = "express";
    public static final String LOCAL = "local";
    public static final String PICKUP = "pickup";

    private final Rates rates;

    public DefaultShippingPolicy(Rates rates) {
        this.rates = rates;
    }

    /** Fees and estimates in minor units / business days. */
    public record Rates(long carrierStandardMinor, long carrierExpressMinor, long freeCarrierAboveMinor,
                        long deliveryMinor, long freeDeliveryAboveMinor, int standardDays, int expressDays,
                        int deliveryDays, int pickupDays) {

        public static Rates defaults() {
            return new Rates(599, 1499, 3500, 499, 5000, 5, 2, 1, 1);
        }
    }

    @Override
    public long fee(DeliveryMethod method, String serviceLevel, long itemsMinor) {
        return switch (method) {
            case PICKUP -> 0;
            case AFROTRANSACT_DELIVERY -> itemsMinor >= rates.freeDeliveryAboveMinor() ? 0 : rates.deliveryMinor();
            case CARRIER -> express(serviceLevel) ? rates.carrierExpressMinor()
                    : itemsMinor >= rates.freeCarrierAboveMinor() ? 0 : rates.carrierStandardMinor();
        };
    }

    @Override
    public int estimatedDays(DeliveryMethod method, String serviceLevel) {
        return switch (method) {
            case PICKUP -> rates.pickupDays();
            case AFROTRANSACT_DELIVERY -> rates.deliveryDays();
            case CARRIER -> express(serviceLevel) ? rates.expressDays() : rates.standardDays();
        };
    }

    @Override
    public String defaultServiceLevel(DeliveryMethod method) {
        return switch (method) {
            case PICKUP -> PICKUP;
            case AFROTRANSACT_DELIVERY -> LOCAL;
            case CARRIER -> STANDARD;
        };
    }

    private static boolean express(String serviceLevel) {
        return serviceLevel != null && EXPRESS.equals(serviceLevel.toLowerCase(Locale.ROOT));
    }
}
