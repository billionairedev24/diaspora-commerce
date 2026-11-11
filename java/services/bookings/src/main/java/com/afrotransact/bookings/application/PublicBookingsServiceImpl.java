package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.bookings.domain.AddOn;
import com.afrotransact.bookings.domain.AreaMatch;
import com.afrotransact.bookings.domain.AreaPolicy;
import com.afrotransact.bookings.domain.BookedService;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.CustomerPlace;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.RatingSummary;
import com.afrotransact.bookings.domain.ServiceArea;
import com.afrotransact.bookings.domain.ServiceAreaMatcher;
import com.afrotransact.bookings.domain.ServiceMode;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.ServiceZone;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.domain.ZoneCity;
import com.afrotransact.bookings.domain.Zones;
import com.afrotransact.bookings.port.GeoClient;
import com.afrotransact.bookings.port.OfferingRepository;
import com.afrotransact.bookings.port.OrgVisibilityRepository;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.bookings.port.ReviewRepository;
import com.afrotransact.bookings.port.ServiceAreaConfigRepository;
import com.afrotransact.bookings.port.ServiceAreaRepository;
import com.afrotransact.bookings.port.TeamRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicBookingsServiceImpl implements PublicBookingsService {

    private static final int SEARCH_LOOKAHEAD_DAYS = 14;
    private static final int MAX_DAYS = 62;
    private static final int CANDIDATES = 300;
    private static final int NEAREST_MAX_ITEMS = 6;
    private static final Pattern ZIP = Pattern.compile("^\\d{5}$");

    private final OfferingRepository offerings;
    private final ProviderRepository providers;
    private final TeamRepository team;
    private final SlotFinder slots;
    private final OrgVisibilityRepository visibility;
    private final ReviewRepository reviews;
    private final GeoClient geo;
    private final ServiceAreaRepository areaRepo;
    private final ServiceAreaConfigRepository areaConfig;
    private final BookingsSettings settings;
    private final Clock clock;

    PublicBookingsServiceImpl(OfferingRepository offerings, ProviderRepository providers, TeamRepository team,
                              SlotFinder slots, OrgVisibilityRepository visibility, ReviewRepository reviews,
                              GeoClient geo, ServiceAreaRepository areaRepo, ServiceAreaConfigRepository areaConfig,
                              BookingsSettings settings, Clock clock) {
        this.areaRepo = areaRepo;
        this.areaConfig = areaConfig;
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
    public SearchResult search(SearchQuery query) {
        String zip = query.zip() == null ? null : query.zip().trim();
        String category = query.category() == null || query.category().isBlank() ? null
                : query.category().trim().toLowerCase(Locale.ROOT);
        int size = Math.clamp(query.size(), 1, 50);
        Sort sort = query.sort() == null ? Sort.NEAREST : query.sort();
        Instant now = Instant.now(clock);
        if (zip == null || zip.isEmpty()) {
            return searchAnywhere(query.text(), category, sort, size, now);
        }
        if (!ZIP.matcher(zip).matches()) {
            throw new BookingsRuleViolation("bookings.invalid-zip", "Enter a 5-digit ZIP code",
                    Map.of("zip", "5 digits"));
        }
        Optional<CustomerPlace> found = geo.place(zip);
        if (found.isEmpty()) {
            return new SearchResult(new Place(zip, "", "", null), "NONE", List.of(), List.of(), List.of());
        }
        CustomerPlace place = found.get();
        AreaPolicy policy = areaConfig.policy();
        Zones zones = new Zones(areaConfig.zones());
        List<ServiceOffering> candidates = offerings.searchNear(query.text(), place.lat(), place.lon(),
                policy.customerTravelMiles(), policy.nearestMaxMiles(), CANDIDATES);
        Map<String, ServiceArea> areas = areaRepo.byOrgs(candidates.stream().map(ServiceOffering::orgId).distinct()
                .toList());
        List<Hit> local = new ArrayList<>();
        List<Hit> nearby = new ArrayList<>();
        for (ServiceOffering o : candidates) {
            ServiceArea area = areas.get(o.orgId());
            if (area == null) {
                continue;
            }
            AreaMatch m = ServiceAreaMatcher.match(o, area, place, policy, zones);
            if (m.local()) {
                local.add(new Hit(o, area, m));
            } else if (m.coverage() == AreaMatch.Coverage.NEAREST) {
                nearby.add(new Hit(o, area, m));
            }
        }
        List<CategoryCount> categories = categoryCounts(local);
        List<Hit> shown = inCategory(local, category);
        List<Hit> suggested = shown.isEmpty() ? inCategory(nearby, category) : List.of();
        String coverage = !shown.isEmpty() ? "LOCAL" : !suggested.isEmpty() ? "NEAREST" : "NONE";
        String zoneName = zones.ofCity(place.city(), place.state()).map(ServiceZone::name).orElse(null);
        return new SearchResult(new Place(zip, place.city(), place.state(), zoneName), coverage,
                listings(shown, sort, size, place, zones, now, false),
                listings(suggested, Sort.NEAREST, Math.min(size, NEAREST_MAX_ITEMS), place, zones, now, true),
                categories);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> suggestions(String text, int limit) {
        if (text == null || text.trim().length() < 2) {
            return List.of();
        }
        return offerings.suggestions(text, Math.clamp(limit, 1, 20));
    }

    private SearchResult searchAnywhere(String text, String category, Sort sort, int size, Instant now) {
        List<Hit> all = offerings.search(text, CANDIDATES).stream()
                .map(o -> new Hit(o, null, null)).toList();
        return new SearchResult(null, "ANY", listings(inCategory(all, category), sort == Sort.NEAREST ? Sort.PRICE
                : sort, size, null, Zones.NONE, now, false), List.of(), categoryCounts(all));
    }

    private static List<Hit> inCategory(List<Hit> hits, String category) {
        return category == null ? hits : hits.stream().filter(h -> h.offering().category().equals(category)).toList();
    }

    private static List<CategoryCount> categoryCounts(List<Hit> hits) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        hits.forEach(h -> counts.merge(h.offering().category(), 1, Integer::sum));
        return counts.entrySet().stream().map(e -> new CategoryCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(CategoryCount::count).reversed()
                        .thenComparing(CategoryCount::category)).toList();
    }

    private List<Listing> listings(List<Hit> hits, Sort sort, int size, CustomerPlace place, Zones zones, Instant now,
                                   boolean nearest) {
        Map<UUID, RatingSummary> ratings = reviews.summaries(hits.stream().map(h -> h.offering().id()).toList());
        List<Listing> all = new ArrayList<>();
        for (Hit h : hits) {
            providers.find(h.offering().orgId()).ifPresent(p -> all.add(new Listing(h.offering(), p,
                    needsAvailability(sort) ? nextAvailable(p, h.offering(), now) : null,
                    ratings.getOrDefault(h.offering().id(), RatingSummary.NONE), areaView(h, place, zones, nearest))));
        }
        all.sort(order(sort));
        List<Listing> page = all.size() > size ? all.subList(0, size) : all;
        if (needsAvailability(sort)) {
            return List.copyOf(page);
        }
        List<Listing> out = new ArrayList<>();
        for (Listing l : page) {
            out.add(new Listing(l.service(), l.provider(), nextAvailable(l.provider(), l.service(), now), l.rating(),
                    l.area()));
        }
        return List.copyOf(out);
    }

    private static boolean needsAvailability(Sort sort) {
        return sort == Sort.SOONEST;
    }

    private static Comparator<Listing> order(Sort sort) {
        Comparator<Listing> byDistance = Comparator.comparingDouble(l -> l.area() == null ? 0 : l.area()
                .distanceMiles());
        Comparator<Listing> byName = Comparator.comparing(l -> l.service().name());
        return switch (sort) {
            case SOONEST -> Comparator.comparing(Listing::nextAvailable, Comparator.nullsLast(Comparator
                    .<Instant>naturalOrder())).thenComparing(byDistance).thenComparing(byName);
            case PRICE -> Comparator.<Listing>comparingLong(l -> l.service().priceMinor()).thenComparing(byDistance)
                    .thenComparing(byName);
            case RATING -> Comparator.<Listing>comparingDouble(l -> -l.rating().average())
                    .thenComparingInt(l -> -l.rating().count()).thenComparing(byDistance).thenComparing(byName);
            case NEAREST -> byDistance.thenComparing(byName);
        };
    }

    private static AreaView areaView(Hit h, CustomerPlace place, Zones zones, boolean nearest) {
        if (h.area() == null || place == null) {
            return null;
        }
        ServiceArea a = h.area();
        List<String> serves = new ArrayList<>();
        if (!nearest && !place.city().isEmpty()) {
            serves.add(label(place.city(), place.state(), a.baseState()));
        }
        if (!nearest) {
            for (ZoneCity c : a.servedCities()) {
                String l = label(c.city(), c.state(), a.baseState());
                if (!serves.contains(l)) {
                    serves.add(l);
                }
            }
        }
        double miles = Math.round(h.match().miles() * 10) / 10.0;
        return new AreaView(a.baseCity(), a.baseState(), miles,
                zones.ofCity(a.baseCity(), a.baseState()).map(ServiceZone::name).orElse(null),
                List.copyOf(serves), nearest);
    }

    private static String label(String city, String state, String homeState) {
        return state.equals(homeState) ? city : city + ", " + state;
    }

    /** One service of one provider with how it relates to the customer's place. */
    private record Hit(ServiceOffering offering, ServiceArea area, AreaMatch match) {
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
