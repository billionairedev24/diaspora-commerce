package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.ContentStateConflict;
import com.afrotransact.content.domain.market.Capabilities;
import com.afrotransact.content.domain.market.Capability;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.MarketChanges;
import com.afrotransact.content.domain.market.MarketStatus;
import com.afrotransact.content.domain.market.Region;
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
    public Country updateCountry(String code, MarketStatus status, String launchNote) {
        Actor actor = access.require(ContentPermissions.MARKETS_EDIT);
        Country before = load(code);
        Country after = before.withStatus(status == null ? before.status() : status,
                launchNote == null ? before.launchNote() : launchNote.isBlank() ? null : launchNote.trim());
        markets.saveDraftCountry(after, actor.subject(), Instant.now(clock));
        audit.record(actor.subject(), "market.country.updated", ENTITY, before.code(), detail(
                "status", before.status() + " -> " + after.status(), "launchNote", String.valueOf(after.launchNote())));
        return load(code);
    }

    @Override
    @Transactional
    public Country setCapabilities(String country, String region, Map<Capability, Boolean> changes) {
        Actor actor = access.require(ContentPermissions.MARKETS_EDIT);
        Country c = load(country);
        Region r = c.requireRegion(region);
        Capabilities caps = r.capabilities();
        Boolean marketplace = changes.get(Capability.MARKETPLACE);
        if (marketplace != null) {
            caps = caps.with(Capability.MARKETPLACE, marketplace);
        }
        for (Map.Entry<Capability, Boolean> e : changes.entrySet()) {
            if (e.getKey() != Capability.MARKETPLACE && e.getValue() != null) {
                caps = caps.with(e.getKey(), e.getValue());
            }
        }
        markets.saveDraftRegion(r.withCapabilities(caps), actor.subject(), Instant.now(clock));
        audit.record(actor.subject(), "market.region.updated", ENTITY, c.code() + "-" + r.code(),
                detail("before", r.capabilities().toString(), "after", caps.toString()));
        return load(country);
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
        Instant now = Instant.now(clock);
        after.regions().forEach(r -> markets.saveDraftRegion(r, actor.subject(), now));
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
