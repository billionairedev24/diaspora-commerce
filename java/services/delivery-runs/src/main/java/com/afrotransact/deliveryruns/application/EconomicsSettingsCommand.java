package com.afrotransact.deliveryruns.application;

/**
 * Staff's numbers for the driver cost model.
 *
 * @param driverCostPerHourMinor  loaded driver cost per hour, minor units
 * @param driverCostPerMileMinor  vehicle cost per mile, minor units
 * @param collectionMinutes       fixed time for the seller sweep and loading
 * @param perStopMinutes          time per doorstep drop
 * @param pickupPointStopMinutes  time at a community pickup point
 * @param handoverMinutesPerOrder time per order handed over at a pickup point
 * @param roundTrip               count the drive back to the origin
 * @param reason                  why (required; logged with who and when)
 */
public record EconomicsSettingsCommand(Long driverCostPerHourMinor, Long driverCostPerMileMinor,
                                       Double collectionMinutes, Double perStopMinutes,
                                       Double pickupPointStopMinutes, Double handoverMinutesPerOrder,
                                       Boolean roundTrip, String reason) {
}
