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
 * @param platformFeePerOrderMinor what the platform keeps on the goods of one order (seller commission and fees),
 *                                minor units: the estimate behind the "Platform fees on goods" line when payments has
 *                                not reported the real commission of an order; 0 = not configured
 */
public record EconomicsSettings(long driverCostPerHourMinor, long driverCostPerMileMinor, double collectionMinutes,
                                double perStopMinutes, double pickupPointStopMinutes, double handoverMinutesPerOrder,
                                boolean roundTrip, long platformFeePerOrderMinor) {

    /** Highest platform fee per order staff can enter: $1,000.00. */
    public static final long MAX_PLATFORM_FEE_MINOR = 100_000;

    /** The cost model without a platform-fee estimate (not configured). */
    public EconomicsSettings(long driverCostPerHourMinor, long driverCostPerMileMinor, double collectionMinutes,
                             double perStopMinutes, double pickupPointStopMinutes, double handoverMinutesPerOrder,
                             boolean roundTrip) {
        this(driverCostPerHourMinor, driverCostPerMileMinor, collectionMinutes, perStopMinutes, pickupPointStopMinutes,
                handoverMinutesPerOrder, roundTrip, 0);
    }

    /** Highest driver cost per hour staff can enter: $1,000.00. */
    public static final long MAX_PER_HOUR_MINOR = 100_000;
    /** Highest vehicle cost per mile staff can enter: $100.00. */
    public static final long MAX_PER_MILE_MINOR = 10_000;
    /** Longest fixed or per-stop time staff can enter, in minutes. */
    public static final double MAX_MINUTES = 1_440;

    public EconomicsSettings {
        if (driverCostPerHourMinor < 0 || driverCostPerMileMinor < 0 || collectionMinutes < 0 || perStopMinutes < 0
                || pickupPointStopMinutes < 0 || handoverMinutesPerOrder < 0 || platformFeePerOrderMinor < 0) {
            throw new IllegalArgumentException("Economics settings cannot be negative");
        }
    }

    /**
     * Staff's numbers, checked: money and minutes between 0 and a sane maximum.
     *
     * @throws RuleViolation {@code delivery-runs.invalid-economics} naming the first number that is out of range
     */
    public static EconomicsSettings checked(Long perHourMinor, Long perMileMinor, Double collection, Double perStop,
                                            Double pickupPointStop, Double handoverPerOrder, Boolean roundTrip,
                                            Long platformFeePerOrderMinor) {
        money("Driver cost per hour", perHourMinor, MAX_PER_HOUR_MINOR);
        money("Vehicle cost per mile", perMileMinor, MAX_PER_MILE_MINOR);
        money("Platform fee per order", platformFeePerOrderMinor, MAX_PLATFORM_FEE_MINOR);
        minutes("Seller sweep and loading time", collection);
        minutes("Time per doorstep stop", perStop);
        minutes("Time at a pickup point", pickupPointStop);
        minutes("Handover time per order", handoverPerOrder);
        if (roundTrip == null) {
            throw new RuleViolation("delivery-runs.invalid-economics", "Say whether the drive back is counted");
        }
        return new EconomicsSettings(perHourMinor, perMileMinor, collection, perStop, pickupPointStop,
                handoverPerOrder, roundTrip, platformFeePerOrderMinor);
    }

    private static void money(String what, Long minor, long max) {
        if (minor == null || minor < 0 || minor > max) {
            throw new RuleViolation("delivery-runs.invalid-economics", what + " is between 0 and " + max / 100
                    + ".00");
        }
    }

    private static void minutes(String what, Double minutes) {
        if (minutes == null || minutes.isNaN() || minutes < 0 || minutes > MAX_MINUTES) {
            throw new RuleViolation("delivery-runs.invalid-economics", what + " is between 0 and "
                    + (long) MAX_MINUTES + " minutes");
        }
    }
}
