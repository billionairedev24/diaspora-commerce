package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.bookings.domain.AddOn;
import com.afrotransact.bookings.domain.BookedService;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.RatingSummary;
import com.afrotransact.bookings.domain.ServiceMode;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.port.GeoClient;
import com.afrotransact.bookings.port.OfferingRepository;
import com.afrotransact.bookings.port.OrgVisibilityRepository;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.bookings.port.ReviewRepository;
import com.afrotransact.bookings.port.TeamRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicBookingsServiceImpl implements PublicBookingsService {

    private static final int SEARCH_LOOKAHEAD_DAYS = 14;
    private static final int MAX_DAYS = 62;
    private static final Pattern ZIP = Pattern.compile("^\\d{5}$");

    private final OfferingRepository offerings;
    private final ProviderRepository providers;
    private final TeamRepository team;
    private final SlotFinder slots;
    private final OrgVisibilityRepository visibility;
    private final ReviewRepository reviews;
    private final GeoClient geo;
    private final BookingsSettings settings;
    private final Clock clock;

    PublicBookingsServiceImpl(OfferingRepository offerings, ProviderRepository providers, TeamRepository team,
                              SlotFinder slots, OrgVisibilityRepository visibility, ReviewRepository reviews,
                              GeoClient geo, BookingsSettings settings, Clock clock) {
        this.reviews = reviews;
        this.geo = geo;
        this.offerings = offerings;
        this.visibility = visibility;
        this.providers = providers;
        this.team = team;
        this.slots = slots;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Listing> search(String text, String category, String zip, int size) {
        Set<String> serving = null;
        if (zip != null && !zip.isBlank()) {
            String z = zip.trim();
            if (!ZIP.matcher(z).matches()) {
                throw new BookingsRuleViolation("bookings.invalid-zip", "Enter a 5-digit ZIP code",
                        Map.of("zip", "5 digits"));
            }
            serving = geo.serviceAreaOwners(z).orElse(Set.of());
        }
        Instant now = Instant.now(clock);
        List<ServiceOffering> found = offerings.search(text, category, serving, Math.clamp(size, 1, 50));
        Map<UUID, RatingSummary> ratings = reviews.summaries(found.stream().map(ServiceOffering::id).toList());
        List<Listing> out = new ArrayList<>();
        for (ServiceOffering o : found) {
            Optional<Provider> p = providers.find(o.orgId());
            p.ifPresent(provider -> out.add(new Listing(o, provider, nextAvailable(provider, o, now),
                    ratings.getOrDefault(o.id(), RatingSummary.NONE))));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderPage provider(String orgId) {
        if (visibility.isHidden(orgId)) {
            throw ProviderSetupServiceImpl.notFound(orgId);
        }
        Provider p = providers.find(orgId).orElseThrow(() -> ProviderSetupServiceImpl.notFound(orgId));
        return new ProviderPage(p, offerings.byOrg(orgId, true), bookableTeam(orgId));
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceDetail service(UUID serviceId) {
        ServiceOffering o = live(serviceId);
        return new ServiceDetail(o, provider(o), slots.candidates(o, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayCount> days(UUID serviceId, LocalDate from, int days, UUID memberId, List<String> addOnIds) {
        ServiceOffering o = live(serviceId);
        Provider p = provider(o);
        int duration = duration(o, addOnIds);
        Instant now = Instant.now(clock);
        LocalDate start = from == null ? LocalDate.ofInstant(now, p.timeZone()) : from;
        List<DayCount> out = new ArrayList<>();
        for (int i = 0; i < Math.clamp(days, 1, MAX_DAYS); i++) {
            LocalDate d = start.plusDays(i);
            out.add(new DayCount(d, open(p, o, duration, d, memberId, now).size()));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenSlot> slots(UUID serviceId, LocalDate date, UUID memberId, List<String> addOnIds) {
        ServiceOffering o = live(serviceId);
        Provider p = provider(o);
        return open(p, o, duration(o, addOnIds), date, memberId, Instant.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public Summary summary(UUID serviceId, List<String> addOnIds, ServiceMode mode) {
        ServiceOffering o = live(serviceId);
        Provider p = provider(o);
        ServiceMode chosen = mode != null ? mode : o.modes().stream().sorted().findFirst().orElseThrow();
        BookedService s = BookedService.of(o, o.pick(addOnIds), chosen, p.displayName(), null);
        return new Summary(o.id(), o.name(), p.displayName(), s.durationMinutes(), s.priceMinor(), s.depositMinor(),
                s.balanceMinor(), s.currency(), chosen, s.addOns().stream().map(AddOn::name).toList(),
                p.policy().label(), p.policy().text(), p.rules().instantBooking(),
                settings.requestSla().toMinutes());
    }

    private List<OpenSlot> open(Provider p, ServiceOffering o, int duration, LocalDate date, UUID memberId,
                                Instant now) {
        Map<Instant, OpenSlot> byStart = new LinkedHashMap<>();
        for (SlotFinder.MemberSlot ms : slots.slots(p, o, duration, date, memberId, null, now)) {
            OpenSlot prev = byStart.get(ms.slot().start());
            List<UUID> members = new ArrayList<>(prev == null ? List.of() : prev.memberIds());
            members.add(ms.member().id());
            byStart.put(ms.slot().start(), new OpenSlot(ms.slot().start(), ms.slot().end(), List.copyOf(members)));
        }
        return List.copyOf(byStart.values());
    }

    private Instant nextAvailable(Provider p, ServiceOffering o, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, p.timeZone());
        for (int i = 0; i < SEARCH_LOOKAHEAD_DAYS; i++) {
            List<SlotFinder.MemberSlot> found = slots.slots(p, o, o.durationMinutes(), today.plusDays(i), null, null,
                    now);
            if (!found.isEmpty()) {
                return found.getFirst().slot().start();
            }
        }
        return null;
    }

    private List<TeamMember> bookableTeam(String orgId) {
        return team.byOrg(orgId).stream().filter(TeamMember::takesBookings).toList();
    }

    private static int duration(ServiceOffering o, List<String> addOnIds) {
        return o.durationMinutes() + o.pick(addOnIds).stream().mapToInt(AddOn::extraMinutes).sum();
    }

    private ServiceOffering live(UUID id) {
        return offerings.find(id).filter(ServiceOffering::active).filter(o -> !visibility.isHidden(o.orgId()))
                .orElseThrow(() -> new NotFoundException("bookings.service-not-found", "Service " + id
                        + " is not available"));
    }

    private Provider provider(ServiceOffering o) {
        return providers.find(o.orgId()).orElseThrow(() -> new BookingsRuleViolation("bookings.provider-not-set-up",
                "This provider is not taking bookings"));
    }
}
