package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.bookings.domain.AreaMatch;
import com.afrotransact.bookings.domain.AreaPolicy;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.CustomerPlace;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.ServiceArea;
import com.afrotransact.bookings.domain.ServiceAreaMatcher;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.Zones;
import com.afrotransact.bookings.port.GeoClient;
import com.afrotransact.bookings.port.OfferingRepository;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.bookings.port.ServiceAlertRepository;
import com.afrotransact.bookings.port.ServiceAlertRepository.Alert;
import com.afrotransact.bookings.port.ServiceAreaConfigRepository;
import com.afrotransact.bookings.port.ServiceAreaRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ServiceAlertServiceImpl implements ServiceAlertService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$");
    private static final Pattern ZIP = Pattern.compile("^\\d{5}$");
    private static final Pattern LOCALE = Pattern.compile("^(en|fr|es|ar|yo)$");

    private final ServiceAlertRepository alerts;
    private final GeoClient geo;
    private final ServiceAreaRepository areas;
    private final ServiceAreaConfigRepository config;
    private final OfferingRepository offerings;
    private final ProviderRepository providers;
    private final DomainEventPublisher events;
    private final BookingsSettings settings;
    private final Clock clock;

    ServiceAlertServiceImpl(ServiceAlertRepository alerts, GeoClient geo, ServiceAreaRepository areas,
                            ServiceAreaConfigRepository config, OfferingRepository offerings,
                            ProviderRepository providers, DomainEventPublisher events, BookingsSettings settings,
                            Clock clock) {
        this.alerts = alerts;
        this.geo = geo;
        this.areas = areas;
        this.config = config;
        this.offerings = offerings;
        this.providers = providers;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void subscribe(String email, String zip, String category, String locale) {
        String mail = email == null ? "" : email.trim();
        if (!EMAIL.matcher(mail).matches() || mail.length() > 254) {
            throw new BookingsRuleViolation("bookings.invalid-alert", "Enter a valid email address",
                    Map.of("email", "Enter a valid email address"));
        }
        if (zip == null || !ZIP.matcher(zip).matches()) {
            throw new BookingsRuleViolation("bookings.invalid-zip", "Enter a 5-digit ZIP code",
                    Map.of("zip", "5 digits"));
        }
        CustomerPlace place = geo.place(zip).orElseThrow(() -> new BookingsRuleViolation("bookings.invalid-zip",
                "We could not find that ZIP code", Map.of("zip", "Unknown ZIP code")));
        String cat = category == null || category.isBlank() ? null : category.trim().toLowerCase(Locale.ROOT);
        String loc = locale != null && LOCALE.matcher(locale).matches() ? locale : "en";
        alerts.open(new Alert(UUID.randomUUID(), mail, zip, place.city(), place.state(), place.lat(), place.lon(),
                cat, loc, Instant.now(clock)));
    }

    @Override
    @Transactional
    public void providerChanged(String orgId) {
        Optional<ServiceArea> area = areas.find(orgId);
        Optional<Provider> provider = providers.find(orgId);
        if (area.isEmpty() || provider.isEmpty()) {
            return;
        }
        AreaPolicy policy = config.policy();
        Zones zones = new Zones(config.zones());
        int reach = Math.max(area.get().radiusMiles(), policy.customerTravelMiles());
        for (Alert a : alerts.openNear(area.get().lat(), area.get().lon(), reach)) {
            CustomerPlace place = new CustomerPlace(a.zip(), a.city(), a.state(), a.lat(), a.lon());
            for (ServiceOffering o : offerings.byOrg(orgId, true)) {
                boolean categoryOk = a.category() == null || a.category().equals(o.category());
                if (categoryOk && ServiceAreaMatcher.match(o, area.get(), place, policy, zones).coverage()
                        == AreaMatch.Coverage.LOCAL) {
                    events.publish(BookingsEvents.SERVICE_ALERT, BookingsEvents.AGGREGATE, a.id().toString(),
                            ServiceAlertPayload.of(a, o, provider.get(), settings.serviceUrlBase()));
                    alerts.markNotified(a.id(), Instant.now(clock));
                    break;
                }
            }
        }
    }
}
