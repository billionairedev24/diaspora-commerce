package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.Coverage;
import com.afrotransact.deliveryruns.domain.CreativeRef;
import com.afrotransact.deliveryruns.application.WindowSwitchResult.Outcome;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.deliveryruns.domain.InvalidTransitionException;
import com.afrotransact.deliveryruns.domain.PaymentSettings;
import com.afrotransact.deliveryruns.domain.PreorderStatus;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.WindowDetails;
import com.afrotransact.deliveryruns.port.MediaGateway;
import com.afrotransact.deliveryruns.port.MediaGateway.MediaAsset;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository.WindowTotals;
import com.afrotransact.deliveryruns.port.PreorderSequences;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PreorderWindowServiceImpl implements PreorderWindowService {

    /** List tabs of the staff console (paused windows only show under all); scheduled / drafts are aliases. */
    private static final Map<String, Set<PreorderStatus>> TABS = Map.of(
            "open", EnumSet.of(PreorderStatus.OPEN),
            "upcoming", EnumSet.of(PreorderStatus.UPCOMING),
            "decision", EnumSet.of(PreorderStatus.AWAITING_DECISION),
            "scheduled", EnumSet.of(PreorderStatus.UPCOMING),
            "closed", EnumSet.of(PreorderStatus.CLOSED, PreorderStatus.DELIVERED, PreorderStatus.CANCELLED),
            "draft", EnumSet.of(PreorderStatus.DRAFT),
            "drafts", EnumSet.of(PreorderStatus.DRAFT));

    /** Most windows one bulk switch may name. */
    static final int MAX_BULK = 100;

    private final PreorderWindowRepository windows;
    private final PreorderOrderRepository orders;
    private final PreorderSequences sequences;
    private final PreorderSettingsRepository settings;
    private final MediaGateway media;
    private final PreorderSupport support;
    private final PreorderEventPublisher events;
    private final PermissionChecker permissions;
    private final Clock clock;

    PreorderWindowServiceImpl(PreorderWindowRepository windows, PreorderOrderRepository orders,
                              PreorderSequences sequences, PreorderSettingsRepository settings,
                              MediaGateway media, PreorderSupport support,
                              PreorderEventPublisher events, PermissionChecker permissions, Clock clock) {
        this.windows = windows;
        this.orders = orders;
        this.sequences = sequences;
        this.settings = settings;
        this.media = media;
        this.support = support;
        this.events = events;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public WindowPage list(String status, String q, int page, int size) {
        permissions.require(DeliveryRunsPermissions.READ);
        Instant now = Instant.now(clock);
        int pageSize = Math.min(Math.max(size, 1), 100);
        int pageNo = Math.max(page, 0);
        List<PreorderWindow> all = windows.findAll();
        Coverage coverage = support.coverage();
        Map<UUID, WindowTotals> totals = orders.totals(all.stream().map(PreorderWindow::id).toList());
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<PreorderWindow> searched = all.stream().filter(w -> query.isEmpty() || matches(w, query)).toList();
        Map<String, Integer> tabs = new LinkedHashMap<>();
        tabs.put("all", searched.size());
        for (String tab : List.of("open", "upcoming", "decision", "closed", "draft")) {
            Set<PreorderStatus> in = TABS.get(tab);
            tabs.put(tab, (int) searched.stream().filter(w -> in.contains(w.status(now))).count());
        }
        Predicate<PreorderWindow> byStatus = statusFilter(status, now);
        List<PreorderWindow> filtered = searched.stream().filter(byStatus).toList();
        List<WindowView> items = filtered.stream().skip((long) pageNo * pageSize).limit(pageSize)
                .map(w -> support.view(w, coverage, totals, now)).toList();
        return new WindowPage(items, filtered.size(), pageNo, pageSize, tabs, coverage.settings().enabled(),
                support.defaultMinOrders());
    }

    @Override
    @Transactional
    public WindowView create(WindowCommand command) {
        permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        WindowDetails details = PreorderSupport.details(command, support.defaultMinOrders());
        String prefix = PreorderWindow.codePrefix(details.targets());
        String code = prefix + "-" + sequences.next("code:" + prefix, 1);
        PreorderWindow saved = windows.insert(PreorderWindow.draft(UUID.randomUUID(), code, details, now));
        rememberAreas(details);
        return support.view(saved, now);
    }

    @Override
    @Transactional(readOnly = true)
    public WindowView get(UUID id) {
        permissions.require(DeliveryRunsPermissions.READ);
        return support.view(support.find(id), Instant.now(clock));
    }

    @Override
    @Transactional
    public WindowView update(UUID id, WindowCommand command, Long expectedVersion) {
        permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        PreorderWindow w = support.lock(id, expectedVersion);
        PaymentSettings payment = support.coverage().settings().payment();
        WindowDetails details = PreorderSupport.details(command, w.details().minOrders());
        PreorderWindow saved = windows.update(w.edit(details, payment, support.pageUrl(), now));
        rememberAreas(details);
        if (!saved.draft()) {
            events.window(DeliveryRunsEvents.PREORDER_WINDOW_UPDATED, saved, payment, now);
        }
        return support.view(saved, now);
    }

    @Override
    @Transactional
    public WindowView publish(UUID id, Long expectedVersion) {
        permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        PreorderWindow w = support.lock(id, expectedVersion);
        PaymentSettings payment = support.coverage().settings().payment();
        PreorderWindow saved = windows.update(w.publish(payment, support.pageUrl(), now));
        events.window(DeliveryRunsEvents.PREORDER_WINDOW_PUBLISHED, saved, payment, now);
        return support.view(saved, now);
    }

    @Override
    @Transactional
    public WindowView pause(UUID id, Long expectedVersion) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        PreorderWindow saved = windows.update(support.lock(id, expectedVersion).pause(now));
        events.window(DeliveryRunsEvents.PREORDER_WINDOW_PAUSED, saved, support.coverage().settings().payment(), now,
                actor.subject());
        return support.view(saved, now);
    }

    @Override
    @Transactional
    public WindowView resume(UUID id, Long expectedVersion) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        PaymentSettings payment = support.coverage().settings().payment();
        PreorderWindow saved = windows.update(support.lock(id, expectedVersion)
                .resume(payment, support.pageUrl(), now));
        events.window(DeliveryRunsEvents.PREORDER_WINDOW_RESUMED, saved, payment, now, actor.subject());
        return support.view(saved, now);
    }

    @Override
    @Transactional
    public WindowView setActive(UUID id, boolean active, Long expectedVersion) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        return switchOne(support.lock(id, expectedVersion), active, actor, now);
    }

    @Override
    @Transactional
    public WindowSwitchResult setActive(List<UUID> ids, boolean active) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BULK) {
            throw new RuleViolation("delivery-runs.invalid-selection",
                    "Choose between 1 and " + MAX_BULK + " pre-orders");
        }
        Instant now = Instant.now(clock);
        List<WindowSwitchResult.Item> items = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            Optional<PreorderWindow> found = windows.lockById(id);
            if (found.isEmpty()) {
                items.add(new WindowSwitchResult.Item(id, Outcome.SKIPPED, null, "delivery-runs.preorder-not-found",
                        "Pre-order not found"));
                continue;
            }
            PreorderWindow w = found.get();
            try {
                boolean flips = w.active() != active;
                WindowView v = switchOne(w, active, actor, now);
                items.add(new WindowSwitchResult.Item(id, flips ? Outcome.SWITCHED : Outcome.UNCHANGED, v, null,
                        null));
            } catch (InvalidTransitionException e) {
                items.add(new WindowSwitchResult.Item(id, Outcome.SKIPPED, support.view(w, now),
                        "delivery-runs.invalid-transition", w.draft() ? "A draft has no switch; publish it first"
                        : "A delivered pre-order cannot be switched"));
            } catch (RuleViolation e) {
                items.add(new WindowSwitchResult.Item(id, Outcome.SKIPPED, support.view(w, now), e.code(),
                        e.getMessage()));
            }
        }
        return new WindowSwitchResult(items);
    }

    /** Flips the window's own switch when it differs (never the master switch) and records who did it. */
    private WindowView switchOne(PreorderWindow w, boolean active, Actor actor, Instant now) {
        PaymentSettings payment = support.coverage().settings().payment();
        PreorderWindow next = w.switchTo(active, payment, support.pageUrl(), now);
        if (next == w) {
            return support.view(w, now);
        }
        PreorderWindow saved = windows.update(next);
        events.window(active ? DeliveryRunsEvents.PREORDER_WINDOW_RESUMED : DeliveryRunsEvents.PREORDER_WINDOW_PAUSED,
                saved, payment, now, actor.subject());
        return support.view(saved, now);
    }

    @Override
    @Transactional
    public WindowView setCreative(UUID id, CreativeSlot slot, UUID assetId, Long expectedVersion) {
        permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        if (slot == null || assetId == null) {
            throw new RuleViolation("delivery-runs.invalid-creative", "A slot and an asset id are required");
        }
        support.find(id);
        MediaAsset asset = media.asset(assetId).orElseThrow(() -> new RuleViolation(
                "delivery-runs.asset-not-found", "media-service has no asset " + assetId));
        if (!asset.ready()) {
            throw new RuleViolation("delivery-runs.asset-not-ready",
                    "The image is " + asset.status() + "; only published (READY) images can be used");
        }
        if (!asset.image()) {
            throw new RuleViolation("delivery-runs.asset-not-image", "Creatives must be images");
        }
        Instant now = Instant.now(clock);
        PreorderWindow w = support.lock(id, expectedVersion);
        PreorderWindow saved = windows.update(w.withCreative(slot,
                new CreativeRef(assetId, asset.url(), asset.width(), asset.height()), now));
        if (!saved.draft()) {
            events.window(DeliveryRunsEvents.PREORDER_WINDOW_UPDATED, saved, support.coverage().settings().payment(),
                    now);
        }
        return support.view(saved, now);
    }

    @Override
    @Transactional
    public WindowView removeCreative(UUID id, CreativeSlot slot, Long expectedVersion) {
        permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        PreorderWindow w = support.lock(id, expectedVersion);
        if (!w.creatives().containsKey(slot)) {
            return support.view(w, now);
        }
        PreorderWindow saved = windows.update(w.withoutCreative(slot, now));
        if (!saved.draft()) {
            events.window(DeliveryRunsEvents.PREORDER_WINDOW_UPDATED, saved, support.coverage().settings().payment(),
                    now);
        }
        return support.view(saved, now);
    }

    /** A saved window's targets become coverage areas (switched on) when they are not yet. */
    private void rememberAreas(WindowDetails details) {
        details.targets().forEach(k -> settings.insertAreaIfMissing(k, true));
    }

    private static Predicate<PreorderWindow> statusFilter(String status, Instant now) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            return w -> true;
        }
        String s = status.trim().toLowerCase(Locale.ROOT);
        Set<PreorderStatus> in = TABS.get(s);
        if (in == null) {
            try {
                in = EnumSet.of(PreorderStatus.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new RuleViolation("delivery-runs.invalid-status", "Unknown status " + status);
            }
        }
        Set<PreorderStatus> wanted = in;
        return w -> wanted.contains(w.status(now));
    }

    private static boolean matches(PreorderWindow w, String q) {
        if (w.details().name().toLowerCase(Locale.ROOT).contains(q) || w.code().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return w.details().targets().stream().map(AreaKey::key)
                .anyMatch(k -> k.toLowerCase(Locale.ROOT).contains(q));
    }
}
