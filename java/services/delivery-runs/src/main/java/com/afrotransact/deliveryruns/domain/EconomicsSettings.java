package com.afrotransact.deliveryruns.domain;

/**
 * Driver cost model of a run (configurable per environment, {@code at.delivery-runs.economics.*}).
 *
 * @param driverCostPerHourMinor  loaded driver cost per hour (wage, insurance), minor units
 * @param driverCostPerMileMinor  vehicle cost per mile (fuel, wear), minor units
 * @param collectionMinutes       fixed time for the seller sweep and loading at the origin
 * @param perStopMinutes          time per doorstep drop, local driving between drops included
 * @param pickupPointStopMinutes  time spent at a community pickup point
 * @param handoverMinutesPerOrder time per order handed over at a pickup point
 * @param roundTrip               count the drive back to the origin
 */
public record EconomicsSettings(long driverCostPerHourMinor, long driverCostPerMileMinor, double collectionMinutes,
                                double perStopMinutes, double pickupPointStopMinutes, double handoverMinutesPerOrder,
                                boolean roundTrip) {

    public EconomicsSettings {
        if (driverCostPerHourMinor < 0 || driverCostPerMileMinor < 0 || collectionMinutes < 0 || perStopMinutes < 0
                || pickupPointStopMinutes < 0 || handoverMinutesPerOrder < 0) {
            throw new IllegalArgumentException("Economics settings cannot be negative");
        }
    }
}
