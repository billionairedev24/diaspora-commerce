package com.afrotransact.deliveryruns.domain;

/**
 * The area a location's runs serve.
 *
 * @param kind        polygon (a geo-service zone) or radius
 * @param geoZoneId   geo-service zone id ({@link AreaKind#POLYGON} only)
 * @param center      reference point of the town: circle centre, and the drive destination for run economics
 * @param radiusMiles circle radius ({@link AreaKind#RADIUS} only), 0 &lt; r &le; 100
 */
public record RunArea(AreaKind kind, String geoZoneId, GeoPoint center, Double radiusMiles) {

    public static final double MAX_RADIUS_MILES = 100;

    public RunArea {
        if (kind == null || center == null) {
            throw new RuleViolation("delivery-runs.invalid-area", "An area needs a kind and a centre point");
        }
        if (kind == AreaKind.POLYGON) {
            if (geoZoneId == null || geoZoneId.isBlank()) {
                throw new RuleViolation("delivery-runs.invalid-area", "A polygon area needs its geo zone id");
            }
            radiusMiles = null;
        } else {
            if (radiusMiles == null || radiusMiles <= 0 || radiusMiles > MAX_RADIUS_MILES) {
                throw new RuleViolation("delivery-runs.invalid-area",
                        "A radius area needs a radius between 0 and " + (int) MAX_RADIUS_MILES + " miles");
            }
            geoZoneId = null;
        }
    }

    public static RunArea polygon(String geoZoneId, GeoPoint center) {
        return new RunArea(AreaKind.POLYGON, geoZoneId, center, null);
    }

    public static RunArea radius(GeoPoint center, double radiusMiles) {
        return new RunArea(AreaKind.RADIUS, null, center, radiusMiles);
    }

    /** For a radius area: whether a point at {@code distanceMiles} from the centre is inside. */
    public boolean withinRadius(double distanceMiles) {
        return kind == AreaKind.RADIUS && distanceMiles <= radiusMiles;
    }
}
