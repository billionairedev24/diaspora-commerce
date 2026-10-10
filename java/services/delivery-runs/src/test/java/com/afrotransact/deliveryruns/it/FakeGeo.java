package com.afrotransact.deliveryruns.it;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.RouteEstimate;
import com.afrotransact.deliveryruns.port.GeoGateway;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import com.afrotransact.deliveryruns.port.GeoZoneRegistry;

/**
 * In-memory geo-service: ZIP centroids, polygon zones as bounding boxes, haversine straight-line miles and a fixed
 * Austin → Bastrop route (32 road miles, 40 minutes).
 */
public class FakeGeo implements GeoGateway, GeoZoneRegistry {

    /** A polygon zone approximated by a lat/lng box. */
    record Box(double minLat, double maxLat, double minLng, double maxLng) {

        boolean contains(GeoPoint p) {
            return p.lat() >= minLat && p.lat() <= maxLat && p.lng() >= minLng && p.lng() <= maxLng;
        }
    }

    private final Map<String, GeoPoint> zips = new ConcurrentHashMap<>();
    private final Map<String, Box> zones = new ConcurrentHashMap<>();
    private final AtomicBoolean down = new AtomicBoolean();
    private final List<String> registered = new ArrayList<>();

    public FakeGeo() {
        zips.put("78602", new GeoPoint(30.1105, -97.3153));
        zips.put("78621", new GeoPoint(30.3494, -97.3703));
        zips.put("78701", new GeoPoint(30.2711, -97.7437));
    }

    void zone(String id, Box box) {
        zones.put(id, box);
    }

    void down(boolean value) {
        down.set(value);
    }

    synchronized List<String> registered() {
        return List.copyOf(registered);
    }

    @Override
    public Optional<GeoPoint> locateZip(String zip) {
        check();
        return Optional.ofNullable(zips.get(zip));
    }

    @Override
    public Set<String> zonesCovering(GeoPoint point, String zoneType) {
        check();
        return zones.entrySet().stream().filter(e -> e.getValue().contains(point)).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    @Override
    public List<Double> straightLineMiles(GeoPoint from, List<GeoPoint> to) {
        check();
        return to.stream().map(p -> haversineMiles(from, p)).toList();
    }

    @Override
    public RouteEstimate route(GeoPoint from, GeoPoint to) {
        check();
        return new RouteEstimate(32, 40, "FAKE");
    }

    @Override
    public synchronized String registerPolygon(String name, String ownerId, String geoJson) {
        check();
        String id = "zone-" + ownerId.substring(ownerId.indexOf(':') + 1);
        registered.add(ownerId);
        return id;
    }

    private void check() {
        if (down.get()) {
            throw new GeoUnavailableException("fake geo is down", null);
        }
    }

    static double haversineMiles(GeoPoint a, GeoPoint b) {
        double r = 3958.8;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(a.lat()))
                * Math.cos(Math.toRadians(b.lat())) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
