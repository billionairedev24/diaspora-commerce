package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.ContentStateConflict;
import com.afrotransact.content.domain.StaleVersionException;
import com.afrotransact.content.domain.market.Capabilities;
import com.afrotransact.content.domain.market.Capability;
import com.afrotransact.content.domain.market.City;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.LocationStatus;
import com.afrotransact.content.domain.market.MarketChanges;
import com.afrotransact.content.domain.market.MarketStatus;
import com.afrotransact.content.domain.market.Region;
import com.afrotransact.content.domain.market.RegionKind;
import com.afrotransact.content.port.MarketRepository;
import com.afrotransact.content.port.MarketRepository.Publication;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MarketServiceImpl implements MarketService {

    private static final String ENTITY = "market";
    private static final Pattern REGION_CODE = Pattern.compile("[A-Z0-9]{1,10}");
    private static final int MAX_REGION_NAME = 80;

    private final MarketRepository markets;
    private final MarketSnapshotCache published;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final DomainEventPublisher events;
    private final Clock clock;

    MarketServiceImpl(MarketRepository markets, MarketSnapshotCache published, ContentAccess access, AuditTrail audit,
                      DomainEventPublisher events, Clock clock) {
        this.markets = markets;
        this.published = published;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Country> draftCountries() {
        viewer();
        return markets.draftCountries();
    }

    @Override
    @Transactional(readOnly = true)
    public Country draftCountry(String code) {
        viewer();
        return load(code);
    }

    @Override
    @Transactional
    public Country updateCountry(String code, MarketStatus status, String launchNote, Long expectedVersion) {
        Actor actor = access.require(ContentPermissions.MARKETS_EDIT);
        Country before = load(code);
        StaleVersionException.check(expectedVersion, before.version(), before.name());
        Country after = before.withStatus(status == null ? before.status() : status,
                launchNote == null ? before.launchNote() : launchNote.isBlank() ? null : launchNote.trim());
        if (!markets.saveDraftCountry(after, actor.subject(), Instant.now(clock))) {
            throw new StaleVersionException(before.name() + " was changed by someone else. Refresh to see the latest.",
                    load(code).version());
        }
        audit.record(actor.subject(), "market.country.updated", ENTITY, before.code(), detail(
                "status", before.status() + " -> " + after.status(), "launchNote", String.valueOf(after.launchNote())));
        return load(code);
    }

    @Override
    @Transactional
    public Country setCapabilities(String country, String region, Map<Capability, Boolean> changes) {
        return updateRegion(country, region, new RegionChange(null, null, changes), null);
    }

    @Override
    @Transactional
    public Country updateRegion(String country, String region, RegionChange change, Long expectedVersion) {
        Actor actor = access.require(ContentPermissions.MARKETS_EDIT);
        Country c = load(country);
        Region r = c.requireRegion(region);
        StaleVersionException.check(expectedVersion, r.version(), r.name());
        Region next = r;
        if (change.status() != null) {
            next = next.withStatus(change.status());
        }
        if (change.everyCity() != null) {
            next = next.withEveryCity(change.everyCity());
        }
        Map<Capability, Boolean> caps = change.capabilities();
        if (!caps.isEmpty()) {
            Capabilities switches = next.capabilities();
            Boolean marketplace = caps.get(Capability.MARKETPLACE);
            if (marketplace != null) {
                switches = switches.with(Capability.MARKETPLACE, marketplace);
            }
            for (Map.Entry<Capability, Boolean> e : caps.entrySet()) {
                if (e.getKey() != Capability.MARKETPLACE && e.getValue() != null) {
                    switches = switches.with(e.getKey(), e.getValue());
                }
            }
            next = next.withCapabilities(switches);
        }
        save(next, actor);
        audit.record(actor.subject(), "market.region.updated", ENTITY, c.code() + "-" + r.code(), detail(
                "status", r.status() + " -> " + next.status(), "everyCity", r.everyCity() + " -> " + next.everyCity(),
                "before", r.capabilities().toString(), "after", next.capabilities().toString()));
        return load(country);
    }

    @Override
    @Transactional
    public Country addRegion(String country, String code, String name) {
        Actor actor = access.require(ContentPermissions.MARKETS_EDIT);
        Country c = load(country);
        String rc = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String rn = name == null ? "" : name.trim();
        if (!REGION_CODE.matcher(rc).matches() || rn.isEmpty() || rn.length() > MAX_REGION_NAME) {
            throw new ContentRuleViolation("content.invalid-region", "A state needs a code (letters and digits, up to "
                    + "10) and a name (up to " + MAX_REGION_NAME + " characters)", Map.of("code", "Invalid"));
        }
        Region existing = c.region(rc).orElse(null);
        if (existing != null) {
            if (existing.status() != LocationStatus.OFF) {
                throw new ContentStateConflict("content.region-in-use", existing.name() + " is already "
                        + existing.status().name().toLowerCase(Locale.ROOT));
            }
            save(existing.withStatus(LocationStatus.PLANNED).withEveryCity(existing.cities().isEmpty()
                    ? false : existing.everyCity()), actor);
        } else {
            RegionKind kind = c.regions().isEmpty() ? RegionKind.STATE : c.regions().get(0).kind();
            markets.insertDraftRegion(new Region(c.code(), rc, rn, kind, Capabilities.allOff(), List.of(), true, false,
                    List.of(), 0), actor.subject(), Instant.now(clock));
        }
        audit.record(actor.subject(), "market.region.added", ENTITY, c.code() + "-" + rc, detail("name", rn));
        return load(country);
    }

    @Override
    @Transactional
    public Country addCities(String country, String region, List<City> cities, Long expectedVersion) {
        if (cities == null || cities.isEmpty()) {
            throw new ContentRuleViolation("content.no-cities", "Name at least one city", Map.of("cities", "Required"));
        }
        return editCities(country, region, expectedVersion, "market.cities.added", cities.size(),
                r -> r.addCities(cities));
    }

    @Override
    @Transactional
    public Country setCityStatus(String country, String region, List<String> names, LocationStatus status,
                                 Long expectedVersion) {
        if (status == null) {
            throw new ContentRuleViolation("content.status-required", "Choose LIVE, PLANNED or OFF",
                    Map.of("status", "Required"));
        }
        return editCities(country, region, expectedVersion, "market.cities.status", names == null ? 0 : names.size(),
                r -> r.setCityStatus(names, status));
    }

    @Override
    @Transactional
    public Country removeCities(String country, String region, List<String> names, Long expectedVersion) {
        return editCities(country, region, expectedVersion, "market.cities.removed", names == null ? 0 : names.size(),
                r -> r.removeCities(names));
    }

    private Country editCities(String country, String region, Long expectedVersion, String action, int count,
                               UnaryOperator<Region> edit) {
        Actor actor = access.require(ContentPermissions.MARKETS_EDIT);
        Country c = load(country);
        Region r = c.requireRegion(region);
        StaleVersionException.check(expectedVersion, r.version(), r.name());
        Region next = edit.apply(r);
        save(next, actor);
        audit.record(actor.subject(), action, ENTITY, c.code() + "-" + r.code(),
                detail("cities", String.valueOf(count), "listed", String.valueOf(next.cities().size())));
        return load(country);
    }

    private void save(Region next, Actor actor) {
        if (!markets.saveDraftRegion(next, actor.subject(), Instant.now(clock))) {
            long current = load(next.country()).region(next.code()).map(Region::version).orElse(0L);
            throw new StaleVersionException(next.name() + " was changed by someone else. Refresh to see the latest.",
                    current);
        }
    }

    @Override
    @Transactional
    public Country activateAll(String country) {
        return bulk(country, true);
    }

    @Override
    @Transactional
    public Country deactivateAll(String country) {
        return bulk(country, false);
    }

    private Country bulk(String country, boolean on) {
        Actor actor = access.require(ContentPermissions.MARKETS_EDIT);
        Country c = load(country);
        Country after = on ? c.activateAll() : c.deactivateAll();
        after.regions().forEach(r -> save(r, actor));
        audit.record(actor.subject(), on ? "market.activate-all" : "market.deactivate-all", ENTITY, c.code(),
                detail("regions", String.valueOf(after.regions().size())));
        return load(country);
    }

    @Override
    @Transactional(readOnly = true)
    public MarketChanges pendingChanges() {
        viewer();
        return diff();
    }

    @Override
    @Transactional
    public PublishResult publish(String note) {
        Actor actor = access.require(ContentPermissions.MARKETS_EDIT);
        MarketChanges changes = diff();
        if (changes.empty()) {
            throw new ContentStateConflict("content.nothing-to-publish", "There are no unpublished changes");
        }
        int version = markets.latestVersion() + 1;
        if (!markets.publish(version, actor.subject(), note, Instant.now(clock))) {
            throw new ConflictException("content.concurrent-publish", "Someone else published at the same time; review and publish again");
        }
        events.publish(ContentEvents.MARKETS_PUBLISHED, ContentEvents.AGGREGATE, "markets",
                new ContentEventPayloads.MarketsPublished(version, actor.subject(), note, changes.changedCountries(),
                        changes.newlyLive(), changes.disabledRegions(), changes.enabledRegions()));
        audit.record(actor.subject(), "market.published", ENTITY, "v" + version, detail(
                "changedCountries", String.join(",", changes.changedCountries()),
                "disabledRegions", String.join(",", changes.disabledRegions()),
                "enabledRegions", String.join(",", changes.enabledRegions())));
        published.invalidate();
        return new PublishResult(version, changes);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Publication> publications() {
        viewer();
        return markets.publications(50);
    }

    private MarketChanges diff() {
        return MarketChanges.between(markets.snapshot(markets.latestVersion()).countries(), markets.draftCountries());
    }

    private Country load(String code) {
        return markets.draftCountry(code)
                .orElseThrow(() -> new NotFoundException("content.country-not-found", "No country " + code));
    }

    private void viewer() {
        access.requireAny(ContentPermissions.MARKETS_VIEW, ContentPermissions.MARKETS_EDIT);
    }

    private static Map<String, Object> detail(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
