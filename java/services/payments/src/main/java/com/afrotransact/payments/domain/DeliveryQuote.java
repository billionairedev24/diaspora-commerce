package com.afrotransact.payments.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The price of one AfroTransact Delivery trip from a seller's pickup location to a customer under one fee-schedule
 * version.
 *
 * @param available         false when the version offers no delivery pricing or the trip is beyond the radius
 * @param unavailableReason NOT_CONFIGURED or BEYOND_MAX_RADIUS (null when available)
 * @param customerCharge    the shopper's delivery charge (booked to 4200 AfroTransact shipping)
 * @param sellerFee         the seller's pickup-delivery fee (booked to 4250 Local delivery fees; zero for Retail)
 * @param sellerFeeSource   DEFAULT, OVERRIDE or RETAIL
 */
public record DeliveryQuote(boolean available, String unavailableReason, UUID scheduleId, int scheduleVersion,
                            String currency, BigDecimal distanceMiles, int packages, BigDecimal maxRadiusMiles,
                            Money customerCharge, Money sellerFee, String sellerFeeSource) {

    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String BEYOND_MAX_RADIUS = "BEYOND_MAX_RADIUS";
}
