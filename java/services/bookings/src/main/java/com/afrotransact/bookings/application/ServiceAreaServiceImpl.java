package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.bookings.domain.AreaKind;
import com.afrotransact.bookings.domain.AreaPolicy;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.CustomerPlace;
import com.afrotransact.bookings.domain.NamedArea;
import com.afrotransact.bookings.domain.RadiusRule;
import com.afrotransact.bookings.domain.ServiceArea;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.ServiceZone;
import com.afrotransact.bookings.domain.ZoneCity;
import com.afrotransact.bookings.domain.Zones;
import com.afrotransact.bookings.port.GeoClient;
import com.afrotransact.bookings.port.OfferingRepository;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.bookings.port.ServiceAreaConfigRepository;
import com.afrotransact.bookings.port.ServiceAreaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ServiceAreaServiceImpl implements ServiceAreaService {

    private static final int SERVED_CITIES_MAX = 40;

    private final ServiceAreaRepository areas;
    private final ServiceAreaConfigRepository config;
    private final OfferingRepository offerings;
    private final ProviderRepository providers;
    private final GeoClient geo;
    private final BookingsAccess access;
    private final Clock clock;

    ServiceAreaServiceImpl(ServiceAreaRepository areas, ServiceAreaConfigRepository config,
                           OfferingRepository offerings, ProviderRepository providers, GeoClient geo,
                           BookingsAccess access, Clock clock) {
        this.areas = areas;
        this.config = config;
        this.offerings = offerings;
        this.providers = providers;
        this.geo = geo;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public AreaView get(String orgId) {
        access.provider(orgId, BookingsPermissions.READ);
        return view(orgId, areas.find(orgId).orElse(null));
    }

    @Override
    @Transactional
    public AreaView save(String orgId, AreaCommand c) {
        access.providerWide(orgId, BookingsPermissions.SERVICES_WRITE);
        providers.find(orgId).orElseThrow(() -> ProviderSetupServiceImpl.notFound(orgId));
        AreaPolicy policy = config.policy();
        RadiusRule bounds = policy.boundsFor(categories(orgId));
        int radius = c.radiusMiles() == null ? bounds.defaultMiles() : c.radiusMiles();
        if (radius < bounds.minMiles() || radius > bounds.maxMiles()) {
            throw new BookingsRuleViolation("bookings.invalid-service-area", "Service area is invalid",
                    Map.of("radiusMiles", "Between " + bounds.minMiles() + " and " + bounds.maxMiles()
                            + " miles for your services"));
        }
        CustomerPlace base = place(c.baseZip());
        List<NamedArea> extras = c.extraAreas() == null ? List.of() : c.extraAreas();
        Zones zones = new Zones(config.zones());
        for (NamedArea a : extras) {
            if (a.kind() == AreaKind.ZONE && zones.zone(a.value()).isEmpty()) {
                throw new BookingsRuleViolation("bookings.invalid-service-area", "Service area is invalid",
                        Map.of("extraAreas", "Unknown zone " + a.value()));
            }
        }
        List<ZoneCity> served = covered(base, radius, extras, zones);
        Instant now = Instant.now(clock);
        ServiceArea saved = areas.save(new ServiceArea(orgId, base.zip(), base.city(), base.state(), base.lat(),
                base.lon(), radius, extras, served, now));
        return view(orgId, saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Preview preview(String orgId, String zip, int radiusMiles) {
        access.provider(orgId, BookingsPermissions.READ);
        CustomerPlace base = place(zip);
        Zones zones = new Zones(config.zones());
        return new Preview(base.city(), base.state(), zones.ofCity(base.city(), base.state()),
                covered(base, Math.clamp(radiusMiles, 1, 150), List.of(), zones));
    }

    private AreaView view(String orgId, ServiceArea area) {
        AreaPolicy policy = config.policy();
        return new AreaView(area, policy.boundsFor(categories(orgId)), policy, config.zones());
    }

    private Set<String> categories(String orgId) {
        Set<String> out = new LinkedHashSet<>();
        for (ServiceOffering o : offerings.byOrg(orgId, true)) {
            out.add(o.category());
        }
        return out;
    }

    private CustomerPlace place(String zip) {
        if (zip == null || !zip.matches("\\d{5}")) {
            throw new BookingsRuleViolation("bookings.invalid-service-area", "Service area is invalid",
                    Map.of("baseZip", "Enter the 5-digit ZIP code of your base location"));
        }
        return geo.place(zip).orElseThrow(() -> new BookingsRuleViolation("bookings.invalid-service-area",
                "Service area is invalid", Map.of("baseZip", "We could not find that ZIP code")));
    }

    /** Cities the radius reaches (nearest first), then named cities and whole zones, without duplicates. */
    private List<ZoneCity> covered(CustomerPlace base, int radius, List<NamedArea> extras, Zones zones) {
        Map<String, ZoneCity> out = new LinkedHashMap<>();
        for (ZoneCity c : geo.citiesNear(base.lat(), base.lon(), radius, SERVED_CITIES_MAX)) {
            out.putIfAbsent(c.city() + "|" + c.state(), c);
        }
        for (NamedArea a : extras) {
            if (a.kind() == AreaKind.CITY) {
                ZoneCity c = new ZoneCity(a.value(), a.state());
                out.putIfAbsent(c.city() + "|" + c.state(), c);
            } else if (a.kind() == AreaKind.ZONE) {
                zones.zone(a.value()).map(ServiceZone::cities).orElse(List.of())
                        .forEach(c -> out.putIfAbsent(c.city() + "|" + c.state(), c));
            }
        }
        return List.copyOf(out.values());
    }
}
