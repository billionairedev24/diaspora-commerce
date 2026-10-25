package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.WindowDistances;
import com.afrotransact.deliveryruns.port.GeoGateway;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import com.afrotransact.deliveryruns.port.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * How far a window delivers: road miles from the run origin (the location's hub, else the configured default) to the
 * centre of the window's location, from geo-service. The location is the one the window names, else the active one
 * matching its first city target. Answers are kept for a few hours: they only change when staff move a location.
 */
@Component
class GeoWindowDistances implements WindowDistances {

    private static final Logger LOG = LoggerFactory.getLogger(GeoWindowDistances.class);
    private static final Duration KEEP = Duration.ofHours(6);

    private record Measured(OptionalDouble miles, Instant until, GeoPoint from, GeoPoint to) {
    }

    private final LocationRepository locations;
    private final GeoGateway geo;
    private final DeliveryRunsSettings settings;
    private final Clock clock;
    private final Map<UUID, Measured> kept = new ConcurrentHashMap<>();

    GeoWindowDistances(LocationRepository locations, GeoGateway geo, DeliveryRunsSettings settings, Clock clock) {
        this.locations = locations;
        this.geo = geo;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public OptionalDouble miles(PreorderWindow window) {
        return locate(window).map(this::miles).orElse(OptionalDouble.empty());
    }

    /** The active location a window is planned for: the one it names, else the one matching its first city. */
    Optional<Location> locate(PreorderWindow w) {
        return locate(w.details().locationId(), w.details().targets());
    }

    /** The active location named by {@code locationId}, else the one matching the first city of {@code targets}. */
    Optional<Location> locate(UUID locationId, List<AreaKey> targets) {
        if (locationId != null) {
            return locations.findById(locationId).filter(Location::active);
        }
        Optional<AreaKey> city = targets.stream().filter(AreaKey::isCity).findFirst();
        if (city.isEmpty()) {
            return Optional.empty();
        }
        AreaKey k = city.get();
        return locations.findAll(true).stream()
                .filter(l -> l.name().equalsIgnoreCase(k.city())
                        && k.region().equalsIgnoreCase(l.details().state() == null ? "" : l.details().state())
                        && k.country().equals(l.details().market().toUpperCase(Locale.ROOT)))
                .findFirst();
    }

    /** Road miles from the run origin to the centre of the location; empty when geo-service cannot say. */
    OptionalDouble miles(Location location) {
        GeoPoint from = location.details().origin() == null ? settings.defaultOrigin() : location.details().origin();
        GeoPoint to = location.details().area().center();
        Instant now = Instant.now(clock);
        Measured m = kept.get(location.id());
        if (m != null && m.until().isAfter(now) && m.from().equals(from) && m.to().equals(to)) {
            return m.miles();
        }
        OptionalDouble miles;
        try {
            miles = OptionalDouble.of(geo.route(from, to).roadMiles());
        } catch (GeoUnavailableException e) {
            LOG.warn("No distance for location {}: {}", location.slug(), e.getMessage());
            return m == null ? OptionalDouble.empty() : m.miles();
        }
        kept.put(location.id(), new Measured(miles, now.plus(KEEP), from, to));
        return miles;
    }
}
