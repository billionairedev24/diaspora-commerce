package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.deliveryruns.application.AdvertorialSources.Info;
import com.afrotransact.deliveryruns.domain.AdvertorialCopy;
import com.afrotransact.deliveryruns.domain.AdvertorialEnd;
import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.AdvertorialStatus;
import com.afrotransact.deliveryruns.domain.InvalidTransitionException;
import com.afrotransact.deliveryruns.domain.LocalAdvertorial;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.AdvertorialRepository;
import com.afrotransact.deliveryruns.port.AdvertorialRepository.Counters;
import com.afrotransact.deliveryruns.port.AttributionRepository;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdvertorialServiceImpl implements AdvertorialService {

    private static final int MAX_LIST = 200;
    private static final List<String> LABELS = List.of("A", "B", "C", "D", "E", "F");

    private final AdvertorialRepository advertorials;
    private final PreorderOrderRepository orders;
    private final AttributionRepository attribution;
    private final AdvertorialSources sources;
    private final PermissionChecker permissions;
    private final Clock clock;

    AdvertorialServiceImpl(AdvertorialRepository advertorials, PreorderOrderRepository orders,
                           AttributionRepository attribution, AdvertorialSources sources,
                           PermissionChecker permissions, Clock clock) {
        this.advertorials = advertorials;
        this.orders = orders;
        this.attribution = attribution;
        this.sources = sources;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdvertorialView> list(AdvertorialSource source, UUID sourceId, AdvertorialStatus status, int limit) {
        permissions.require(DeliveryRunsPermissions.READ);
        return views(advertorials.find(source, sourceId, status, Math.min(Math.max(limit, 1), MAX_LIST)));
    }

    @Override
    @Transactional(readOnly = true)
    public AdvertorialView get(UUID id) {
        permissions.require(DeliveryRunsPermissions.READ);
        return views(List.of(find(id))).get(0);
    }

    @Override
    @Transactional
    public AdvertorialView generate(AdvertorialSource source, UUID sourceId) {
        permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        Info info = source(source, sourceId, now);
        if (info.cancelled() || info.closes() != null && !now.isBefore(info.closes())) {
            throw new InvalidTransitionException(info.status(), "ADVERTORIAL");
        }
        Optional<LocalAdvertorial> existing = advertorials.findBySource(source, sourceId).stream()
                .filter(LocalAdvertorial::generated).findFirst();
        LocalAdvertorial saved;
        if (existing.isPresent()) {
            LocalAdvertorial redrafted = existing.get().redraft(info.draft(), now);
            saved = redrafted == existing.get() ? redrafted : advertorials.update(redrafted);
        } else {
            saved = insert(LocalAdvertorial.generated(UUID.randomUUID(), source, sourceId, info.locationId(),
                    info.draft(), now));
        }
        return views(List.of(saved)).get(0);
    }

    @Override
    @Transactional
    public AdvertorialView create(AdvertorialCommand c) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        if (c.source() == null || c.sourceId() == null) {
            throw new RuleViolation("delivery-runs.invalid-advertorial", "Say which window or run it is for");
        }
        Instant now = Instant.now(clock);
        Info info = source(c.source(), c.sourceId(), now);
        if (info.cancelled()) {
            throw new InvalidTransitionException(info.status(), "ADVERTORIAL");
        }
        Set<String> taken = advertorials.findBySource(c.source(), c.sourceId()).stream()
                .map(LocalAdvertorial::variant).collect(Collectors.toSet());
        String variant = c.variant() != null && !c.variant().isBlank() ? c.variant().trim() : LABELS.stream()
                .filter(l -> !taken.contains(l)).findFirst().orElse("Z");
        LocalAdvertorial created = LocalAdvertorial.authored(UUID.randomUUID(), c.source(), c.sourceId(),
                info.locationId(), variant, c.weight() == null ? 1 : c.weight(),
                c.priority() == null ? 0 : c.priority(), copy(c), c.startsAt(), c.endsAt(),
                c.featured() == null ? List.of() : c.featured(), actor.subject(), now);
        return views(List.of(insert(created))).get(0);
    }

    @Override
    @Transactional
    public AdvertorialView update(UUID id, AdvertorialCommand c, Long expectedVersion) {
        permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        LocalAdvertorial current = checked(id, expectedVersion);
        LocalAdvertorial edited = current.edit(copy(c), c.variant() == null || c.variant().isBlank()
                        ? current.variant() : c.variant().trim(), c.weight() == null ? current.weight() : c.weight(),
                c.priority() == null ? current.priority() : c.priority(), c.startsAt(), c.endsAt(),
                c.featured() == null ? current.featured() : c.featured(), Instant.now(clock));
        return views(List.of(save(edited))).get(0);
    }

    @Override
    @Transactional
    public AdvertorialView publish(UUID id, Long expectedVersion) {
        permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        return views(List.of(save(checked(id, expectedVersion).publish(Instant.now(clock))))).get(0);
    }

    @Override
    @Transactional
    public AdvertorialView pause(UUID id, Long expectedVersion) {
        permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        return views(List.of(save(checked(id, expectedVersion).pause(Instant.now(clock))))).get(0);
    }

    @Override
    @Transactional
    public AdvertorialView end(UUID id, Long expectedVersion) {
        permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        return views(List.of(save(checked(id, expectedVersion).end(AdvertorialEnd.STAFF, Instant.now(clock)))))
                .get(0);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private LocalAdvertorial find(UUID id) {
        return advertorials.findById(id).orElseThrow(() -> new NotFoundException(
                "delivery-runs.advertorial-not-found", "Advertorial not found"));
    }

    private LocalAdvertorial checked(UUID id, Long expectedVersion) {
        LocalAdvertorial current = find(id);
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new VersionMismatchException("The advertorial changed since version " + expectedVersion
                    + " (now " + current.version() + "); reload and retry", current.version());
        }
        return current;
    }

    private Info source(AdvertorialSource kind, UUID id, Instant now) {
        return sources.find(kind, id, now).orElseThrow(() -> new NotFoundException(
                kind == AdvertorialSource.RUN ? "delivery-runs.run-not-found" : "delivery-runs.preorder-not-found",
                kind == AdvertorialSource.RUN ? "Run not found" : "Pre-order not found"));
    }

    private LocalAdvertorial insert(LocalAdvertorial a) {
        try {
            return advertorials.insert(a);
        } catch (DuplicateEntryException e) {
            throw new RuleViolation("delivery-runs.advertorial-variant-taken", e.getMessage());
        }
    }

    private LocalAdvertorial save(LocalAdvertorial a) {
        try {
            return advertorials.update(a);
        } catch (DuplicateEntryException e) {
            throw new RuleViolation("delivery-runs.advertorial-variant-taken", e.getMessage());
        }
    }

    private static AdvertorialCopy copy(AdvertorialCommand c) {
        return new AdvertorialCopy(c.headline(), c.body(), c.ctaLabel());
    }

    /** The staff view of these advertorials: source, whether they serve now, views, clicks and attributed orders. */
    private List<AdvertorialView> views(List<LocalAdvertorial> list) {
        Instant now = Instant.now(clock);
        Map<UUID, Counters> counters = advertorials.counters(list.stream().map(LocalAdvertorial::id).toList());
        Map<String, Integer> attributed = new HashMap<>(orders.attributedOrders(list.stream()
                .filter(a -> a.source() == AdvertorialSource.WINDOW).map(LocalAdvertorial::creative).toList()));
        attributed.putAll(attribution.runOrdersByCreative(list.stream()
                .filter(a -> a.source() == AdvertorialSource.RUN).map(LocalAdvertorial::creative).toList()));
        Map<UUID, Optional<Info>> infos = new HashMap<>();
        return list.stream().map(a -> {
            Optional<Info> info = infos.computeIfAbsent(a.sourceId(), id -> sources.find(a.source(), id, now));
            Counters c = counters.getOrDefault(a.id(), new Counters(0, 0));
            int sold = attributed.getOrDefault(a.creative(), 0);
            return new AdvertorialView(a, info.map(Info::name).orElse(null), info.map(Info::code).orElse(null),
                    info.map(Info::status).orElse(null), info.map(Info::closes).orElse(null),
                    info.map(Info::locationName).orElse(null),
                    a.servesAt(now) && info.map(Info::open).orElse(false),
                    AdvertorialView.Results.of(c.views(), c.clicks(), sold));
        }).toList();
    }
}
