package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.TakeHomeCalculator;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.port.OfferingRepository;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.bookings.port.TeamRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ServiceCatalogServiceImpl implements ServiceCatalogService {

    private final OfferingRepository offerings;
    private final ProviderRepository providers;
    private final TeamRepository team;
    private final TakeHomeCalculator takeHome;
    private final ProviderFees fees;
    private final BookingsAccess access;
    private final DomainEventPublisher events;
    private final Clock clock;

    ServiceCatalogServiceImpl(OfferingRepository offerings, ProviderRepository providers, TeamRepository team,
                              TakeHomeCalculator takeHome, ProviderFees fees, BookingsAccess access,
                              DomainEventPublisher events, Clock clock) {
        this.fees = fees;
        this.offerings = offerings;
        this.providers = providers;
        this.team = team;
        this.takeHome = takeHome;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ServiceView create(String orgId, ServiceCommand c) {
        access.providerWide(orgId, BookingsPermissions.SERVICES_WRITE);
        Provider p = providers.find(orgId).orElseThrow(() -> ProviderSetupServiceImpl.notFound(orgId));
        Instant now = Instant.now(clock);
        ServiceOffering created = offerings.insert(new ServiceOffering(UUID.randomUUID(), orgId, c.name(),
                c.description(), c.category(), c.durationMinutes(), c.priceMinor(), c.depositMinor(), p.currency(),
                c.modes(), c.addOns(), Set.of(), true, now, now, 0));
        return publish(created);
    }

    @Override
    @Transactional
    public ServiceView update(String orgId, UUID serviceId, ServiceCommand c) {
        access.providerWide(orgId, BookingsPermissions.SERVICES_WRITE);
        ServiceOffering current = require(orgId, serviceId);
        return publish(offerings.update(current.update(c.name(), c.description(), c.category(), c.durationMinutes(),
                c.priceMinor(), c.depositMinor(), c.modes(), c.addOns(), Instant.now(clock))));
    }

    @Override
    @Transactional
    public ServiceView setActive(String orgId, UUID serviceId, boolean active) {
        access.providerWide(orgId, BookingsPermissions.SERVICES_WRITE);
        return publish(offerings.update(require(orgId, serviceId).withActive(active, Instant.now(clock))));
    }

    @Override
    @Transactional
    public ServiceView assignMembers(String orgId, UUID serviceId, Set<UUID> memberIds) {
        access.providerWide(orgId, BookingsPermissions.TEAM_MANAGE);
        ServiceOffering current = require(orgId, serviceId);
        for (UUID id : memberIds) {
            TeamMember m = team.find(id).filter(x -> x.orgId().equals(orgId))
                    .orElseThrow(() -> new NotFoundException("bookings.member-not-found",
                            "Team member " + id + " not found"));
            if (!m.role().canBeBooked()) {
                throw new BookingsRuleViolation("bookings.member-not-bookable", m.displayName()
                        + " can't perform services (" + m.role() + ")");
            }
        }
        return publish(offerings.update(current.withMembers(memberIds, Instant.now(clock))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceView> list(String orgId) {
        access.provider(orgId, BookingsPermissions.READ);
        return offerings.byOrg(orgId, false).stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceView get(String orgId, UUID serviceId) {
        access.provider(orgId, BookingsPermissions.READ);
        return view(require(orgId, serviceId));
    }

    private ServiceOffering require(String orgId, UUID id) {
        return offerings.find(id).filter(o -> o.orgId().equals(orgId))
                .orElseThrow(() -> new NotFoundException("bookings.service-not-found", "Service " + id + " not found"));
    }

    private ServiceView publish(ServiceOffering o) {
        events.publish(BookingsEvents.SERVICE_UPSERTED, BookingsEvents.AGGREGATE, o.id().toString(),
                ServiceEventPayload.of(o));
        return view(o);
    }

    private ServiceView view(ServiceOffering o) {
        return new ServiceView(o, takeHome.takeHome(fees.forOrg(o.orgId()), o.priceMinor(), o.depositMinor(),
                o.currency()));
    }
}
