package com.afrotransact.bookings.domain;

/**
 * The matching rule, pure and deterministic (see docs/engineering/service-areas.md). With d the straight-line
 * distance from the provider's base to the customer's ZIP centroid, a provider serves the customer when any holds:
 * <ol>
 *   <li>the service comes to the customer (AT_HOME) and d &lt;= the provider's own declared radius: they cannot
 *       travel farther, whatever the shopper chose;</li>
 *   <li>the customer goes to the provider (IN_PERSON) and d is within the distance the shopper chose (any distance
 *       when the shopper chose "Any"): the shopper decides how far to go, the platform sets no limit;</li>
 *   <li>the customer's ZIP, city or metro zone is one of the provider's named extra areas.</li>
 * </ol>
 * Distances are inclusive: exactly the radius still matches.
 */
public final class ServiceAreaMatcher {

    private ServiceAreaMatcher() {
    }

    public static AreaMatch match(ServiceOffering offering, ServiceArea area, CustomerPlace customer,
                                  ShopperRadius shopper, Zones zones) {
        double miles = distanceMiles(area.lat(), area.lon(), customer.lat(), customer.lon());
        if (offering.modes().contains(ServiceMode.AT_HOME) && miles <= area.radiusMiles()) {
            return new AreaMatch(AreaMatch.Coverage.LOCAL, AreaMatch.Reason.RADIUS, miles);
        }
        if (offering.modes().contains(ServiceMode.IN_PERSON) && shopper.reaches(miles)) {
            return new AreaMatch(AreaMatch.Coverage.LOCAL, AreaMatch.Reason.SHOPPER_RADIUS, miles);
        }
        if (area.extraAreas().stream().anyMatch(a -> a.covers(customer, zones))) {
            return new AreaMatch(AreaMatch.Coverage.LOCAL, AreaMatch.Reason.NAMED_AREA, miles);
        }
        return new AreaMatch(AreaMatch.Coverage.NONE, AreaMatch.Reason.NONE, miles);
    }

    private static final double EARTH_RADIUS_MILES = 3958.8;

    /** Great-circle (haversine) distance in miles. */
    public static double distanceMiles(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_MILES * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
