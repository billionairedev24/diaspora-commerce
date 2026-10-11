package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.afrotransact.deliveryruns.domain.AreaChanges;
import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.Coverage;
import com.afrotransact.deliveryruns.domain.CoverageArea;
import com.afrotransact.deliveryruns.domain.PreorderSettings;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RegionCoverage;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.PlaceDirectory;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PreorderSettingsServiceImpl implements PreorderSettingsService {

    /** The design's demo coverage areas (at-preorder.js DEF_AREAS): seeded in the local profile only. */
    static final List<String> DEMO_AREAS_ON = List.of("US/TX/Lubbock", "US/TX/Amarillo", "US/TX/El Paso",
            "US/TX/Midland", "US/TX/Odessa", "CA/ON/Toronto", "CA/ON/Mississauga", "CA/ON/Brampton");
    static final List<String> DEMO_AREAS_OFF = List.of("US/TX/Laredo");
    static final int PAGE = 12;

    private static final Pattern COUNTRY = Pattern.compile("^[A-Za-z]{2}$");

    private final PreorderSettingsRepository settings;
    private final PreorderWindowRepository windows;
    private final PlaceDirectory places;
    private final PreorderEventPublisher events;
    private final PermissionChecker permissions;
    private final Clock clock;

    PreorderSettingsServiceImpl(PreorderSettingsRepository settings, PreorderWindowRepository windows,
                                PlaceDirectory places, PreorderEventPublisher events, PermissionChecker permissions,
                                Clock clock) {
        this.settings = settings;
        this.windows = windows;
        this.places = places;
        this.events = events;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PreorderSettings get() {
        permissions.require(DeliveryRunsPermissions.READ);
        return settings.settings();
    }

    @Override
    @Transactional
    public PreorderSettings update(SettingsCommand c, Long expectedVersion) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        PreorderSettings current = settings.settings();
        PreorderSupport.requireVersion(current.version(), expectedVersion, "Pre-order settings");
        PreorderSettings changed = current.change(c.enabled() == null ? current.enabled() : c.enabled(),
                c.fallback() == null ? current.fallback() : c.fallback(),
                c.placements() == null ? current.placements() : c.placements(),
                c.payment() == null ? current.payment() : c.payment(), Instant.now(clock));
        PreorderSettings saved = settings.update(changed);
        events.settingsChanged(saved, actor.subject());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public AreaPage areas(String country, String region, String q, int page, int size) {
        permissions.require(DeliveryRunsPermissions.READ);
        return page(country, region, q, page, size);
    }

    @Override
    @Transactional
    public AreaPage addArea(String key) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        AreaKey k = AreaKey.parse(key);
        try {
            settings.insertArea(k, true);
        } catch (DuplicateEntryException e) {
            throw new ConflictException("delivery-runs.area-exists", label(k) + " is already a pre-order area");
        }
        events.settingsChanged(settings.settings(), actor.subject());
        return page(k.country(), k.region(), null, 0, PAGE);
    }

    @Override
    @Transactional
    public AreaPage toggle(List<String> keys, boolean on) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        if (keys == null || keys.isEmpty()) {
            throw new RuleViolation("delivery-runs.invalid-area", "Name at least one country, region or area");
        }
        List<CoverageArea> all = settings.areas();
        Set<AreaKey> matched = new LinkedHashSet<>();
        String firstCountry = null;
        String firstRegion = null;
        boolean firstIsArea = false;
        for (String raw : keys) {
            String key = raw == null ? "" : raw.trim();
            if (COUNTRY.matcher(key).matches()) {
                String c = key.toUpperCase(Locale.ROOT);
                all.stream().filter(a -> a.key().country().equals(c)).forEach(a -> matched.add(a.key()));
                continue;
            }
            AreaKey k = AreaKey.parse(key);
            if (firstCountry == null) {
                firstCountry = k.country();
                firstRegion = k.region();
                firstIsArea = k.isCity();
            }
            all.stream().filter(a -> k.isCity() ? a.key().sameAs(k) : a.key().overlaps(k))
                    .forEach(a -> matched.add(a.key()));
        }
        if (matched.isEmpty()) {
            throw new NotFoundException("delivery-runs.area-not-found", "No pre-order area matches " + keys);
        }
        settings.setOn(matched, on);
        events.settingsChanged(settings.settings(), actor.subject());
        if (firstCountry == null) {
            return page(null, null, null, 0, PAGE);
        }
        return firstIsArea ? page(firstCountry, firstRegion, null, 0, PAGE) : page(firstCountry, null, null, 0, PAGE);
    }

    @Override
    @Transactional
    public AreaPage removeArea(String key) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        AreaKey k = AreaKey.parse(key);
        settings.lockRegion(k.regionKey());
        Coverage before = coverage();
        CoverageArea row = before.find(k).orElseThrow(() -> new NotFoundException("delivery-runs.area-not-found",
                label(k) + " is not a pre-order area"));
        AreaChanges change = new AreaChanges(List.of(), List.of(row), List.of());
        refuseStranding(before, before.apply(change), Instant.now(clock));
        if (!settings.deleteArea(k)) {
            throw new NotFoundException("delivery-runs.area-not-found", label(k) + " is not a pre-order area");
        }
        events.settingsChanged(settings.settings(), actor.subject());
        return page(k.country(), k.region(), null, 0, PAGE);
    }

    @Override
    @Transactional(readOnly = true)
    public RegionCoverageView region(String country, String region) {
        permissions.require(DeliveryRunsPermissions.READ);
        return regionView(new AreaKey(country, region, null), coverage(), Instant.now(clock));
    }

    @Override
    @Transactional
    public RegionCoverageView updateRegion(RegionCoverage desired, Long expectedVersion) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        AreaKey region = desired.region();
        settings.lockRegion(region);
        Coverage before = coverage();
        PreorderSupport.requireVersion(before.regionVersion(region), expectedVersion,
                "Coverage of " + places.regionName(region.country(), region.region()));
        AreaChanges changes = before.changes(desired);
        if (changes.empty()) {
            return regionView(region, before, now);
        }
        refuseStranding(before, before.apply(changes), now);
        settings.apply(changes);
        events.areasChanged(region, changes, actor.subject(), now);
        return regionView(region, coverage(), now);
    }

    @Override
    public PlaceList places(String country, String region, String q) {
        permissions.require(DeliveryRunsPermissions.READ);
        String c = blankToNull(country);
        List<PlaceDirectory.Place> items = q != null && q.trim().length() >= 2 ? places.search(q, 8)
                : places.browse(c, blankToNull(region));
        return new PlaceList(items, c == null ? (q != null && q.trim().length() >= 2 ? places.regionLabel("US")
                : "State") : places.regionLabel(c));
    }

    @Override
    @Transactional
    public int seedDemoAreas() {
        int added = 0;
        for (String k : DEMO_AREAS_ON) {
            added += settings.insertAreaIfMissing(AreaKey.parse(k), true) ? 1 : 0;
        }
        for (String k : DEMO_AREAS_OFF) {
            added += settings.insertAreaIfMissing(AreaKey.parse(k), false) ? 1 : 0;
        }
        return added;
    }

    private Coverage coverage() {
        return new Coverage(settings.settings(), settings.areas());
    }

    /** Published windows that are not delivered yet: they still sell (or will sell, or deliver) to their targets. */
    private List<PreorderWindow> liveWindows(Instant now) {
        return windows.findPublished().stream().filter(w -> w.switchable(now)).toList();
    }

    /** 409 when the change would leave a live window's target uncovered (removed, not just switched off). */
    private void refuseStranding(Coverage before, Coverage after, Instant now) {
        List<PreorderWindow> live = liveWindows(now);
        List<AreaKey> stranded = before.stranded(after,
                live.stream().flatMap(w -> w.details().targets().stream()).toList());
        if (stranded.isEmpty()) {
            return;
        }
        String places = stranded.stream().map(this::label).distinct().sorted().reduce((a, b) -> a + ", " + b)
                .orElse("");
        String names = live.stream().filter(w -> w.details().targets().stream().anyMatch(
                t -> stranded.stream().anyMatch(t::sameAs))).map(w -> w.details().name()).distinct().sorted()
                .reduce((a, b) -> a + ", " + b).orElse("");
        throw new ConflictException("delivery-runs.area-in-use", places + " can't be removed while " + names
                + (names.contains(", ") ? " sell" : " sells") + " there. Switch it off instead, or change those "
                + "pre-orders first.");
    }

    private RegionCoverageView regionView(AreaKey region, Coverage coverage, Instant now) {
        AreaKey r = region.regionKey();
        List<PreorderWindow> live = liveWindows(now);
        RegionCoverageView.RegionRow whole = null;
        List<RegionCoverageView.RegionRow> cities = new ArrayList<>();
        String regionName = places.regionName(r.country(), r.region());
        for (CoverageArea a : coverage.region(r)) {
            List<String> sellers = live.stream()
                    .filter(w -> w.details().targets().stream().anyMatch(t -> t.overlaps(a.key())))
                    .map(w -> w.details().name()).toList();
            RegionCoverageView.RegionRow row = new RegionCoverageView.RegionRow(a.key().key(),
                    a.key().isCity() ? a.key().city() : "Every city in " + regionName, a.on(), a.version(), sellers);
            if (a.key().isCity()) {
                cities.add(row);
            } else {
                whole = row;
            }
        }
        return new RegionCoverageView(r.country(), r.region(), places.countryName(r.country()), regionName,
                places.regionLabel(r.country()), whole, cities, coverage.regionVersion(r));
    }

    /** {@code areaLabel} of the design: "Lubbock, TX", "All of Texas", with the country outside the US. */
    String label(AreaKey k) {
        String base = k.isCity() ? k.city() + ", " + k.region() : "All of " + places.regionName(k.country(), k.region());
        return "US".equals(k.country()) ? base : base + " · " + places.countryName(k.country());
    }

    private AreaPage page(String country, String region, String q, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), 100);
        int pageNo = Math.max(page, 0);
        List<CoverageArea> all = settings.areas();
        List<AreaKey> targets = windows.findPublished().stream()
                .flatMap((PreorderWindow w) -> w.details().targets().stream()).toList();
        String c = blankToNull(country) == null ? null : country.trim().toUpperCase(Locale.ROOT);
        String r = c == null || blankToNull(region) == null ? null : region.trim().toUpperCase(Locale.ROOT);
        List<AreaPage.Row> rows = new ArrayList<>();
        String level;
        if (c == null) {
            level = "COUNTRY";
            group(all, a -> a.key().country()).forEach((code, list) -> {
                int regions = (int) list.stream().map(a -> a.key().region()).distinct().count();
                int cities = (int) list.stream().filter(a -> a.key().isCity()).count();
                String label = places.regionLabel(code).toLowerCase(Locale.ROOT);
                rows.add(new AreaPage.Row(code, places.countryName(code), regions + " " + label
                        + (regions > 1 ? "s" : "") + " · " + cities + " cities",
                        (int) targets.stream().filter(t -> t.country().equals(code)).count(), onCount(list),
                        list.size(), false));
            });
            rows.sort(Comparator.comparing(AreaPage.Row::name));
        } else if (r == null) {
            level = "REGION";
            group(all.stream().filter(a -> a.key().country().equals(c)).toList(), a -> a.key().region())
                    .forEach((code, list) -> {
                        boolean whole = list.stream().anyMatch(a -> !a.key().isCity());
                        long cities = list.stream().filter(a -> a.key().isCity()).count();
                        String coverage = (whole ? "Every city" + (cities > 0 ? " + " : "") : "")
                                + (cities > 0 ? cities + " cit" + (cities > 1 ? "ies" : "y") : "");
                        AreaKey regionKey = new AreaKey(c, code, null);
                        rows.add(new AreaPage.Row(regionKey.key(), places.regionName(c, code), coverage,
                                (int) targets.stream().filter(t -> t.overlaps(regionKey)).count(), onCount(list),
                                list.size(), false));
                    });
            rows.sort(Comparator.comparing(AreaPage.Row::name));
        } else {
            level = "AREA";
            String regionName = places.regionName(c, r);
            String regionLabel = places.regionLabel(c).toLowerCase(Locale.ROOT);
            all.stream().filter(a -> a.key().country().equals(c) && a.key().region().equals(r))
                    .sorted(Comparator.comparing((CoverageArea a) -> a.key().isCity() ? 1 : 0)
                            .thenComparing(a -> a.key().isCity() ? a.key().city().toLowerCase(Locale.ROOT) : ""))
                    .forEach(a -> rows.add(new AreaPage.Row(a.key().key(),
                            a.key().isCity() ? a.key().city() : "Every city in " + regionName,
                            a.key().isCity() ? "City" : "Whole " + regionLabel,
                            (int) targets.stream().filter(t -> t.overlaps(a.key())).count(), a.on() ? 1 : 0, 1,
                            true)));
        }
        String ql = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<AreaPage.Row> filtered = rows.stream()
                .filter(x -> ql.isEmpty() || x.name().toLowerCase(Locale.ROOT).contains(ql)).toList();
        int on = onCount(all);
        List<AreaPage.Saved> saved = all.stream().filter(CoverageArea::on)
                .map(a -> new AreaPage.Saved(a.key().key(), label(a.key())))
                .sorted(Comparator.comparing(AreaPage.Saved::label)).toList();
        return new AreaPage(level, c, r, c == null ? null : places.countryName(c),
                r == null ? null : places.regionName(c, r), c == null ? "State" : places.regionLabel(c),
                filtered.stream().skip((long) pageNo * pageSize).limit(pageSize).toList(), filtered.size(), pageNo,
                pageSize, on, all.size() - on, saved);
    }

    private static Map<String, List<CoverageArea>> group(List<CoverageArea> areas,
                                                         Function<CoverageArea, String> by) {
        Map<String, List<CoverageArea>> out = new LinkedHashMap<>();
        areas.forEach(a -> out.computeIfAbsent(by.apply(a), x -> new ArrayList<>()).add(a));
        return out;
    }

    private static int onCount(List<CoverageArea> areas) {
        return (int) areas.stream().filter(CoverageArea::on).count();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
