package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.site.SiteConfig;
import com.afrotransact.content.domain.site.SiteConfigPolicy;
import com.afrotransact.content.port.LegalRepository;
import com.afrotransact.content.port.SiteConfigRepository;
import com.afrotransact.content.port.SiteConfigRepository.RevisionSummary;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SiteConfigServiceImpl implements SiteConfigService {

    private final SiteConfigRepository configs;
    private final LegalRepository legal;
    private final SiteConfigPolicy policy;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final DomainEventPublisher events;
    private final ContentSettings settings;
    private final Clock clock;

    SiteConfigServiceImpl(SiteConfigRepository configs, LegalRepository legal, SiteConfigPolicy policy,
                          ContentAccess access, AuditTrail audit, DomainEventPublisher events,
                          ContentSettings settings, Clock clock) {
        this.configs = configs;
        this.legal = legal;
        this.policy = policy;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PublishedSiteConfig published() {
        SiteConfig config = latest();
        List<LegalSummary> docs = legal.allCurrent().stream()
                .sorted(Comparator.comparing(v -> v.type().ordinal()))
                .map(v -> LegalSummary.of(v, settings.legalBaseHref()))
                .toList();
        return new PublishedSiteConfig(config, docs, Fingerprint.of("site", config.revision(), legal.stamp()));
    }

    @Override
    @Transactional(readOnly = true)
    public SiteConfig current() {
        access.requireAny(ContentPermissions.SITE_VIEW, ContentPermissions.SITE_EDIT);
        return latest();
    }

    @Override
    @Transactional
    public SiteConfig update(SiteConfig draft, int expectedRevision, String summary) {
        Actor actor = access.require(ContentPermissions.SITE_EDIT);
        SiteConfig current = latest();
        if (current.revision() != expectedRevision) {
            throw new ConflictException("content.stale-revision", "The configuration changed (now revision "
                    + current.revision() + "); reload and apply your edits again");
        }
        SiteConfig next = draft.asRevision(current.revision() + 1, actor.subject(), Instant.now(clock));
        policy.validate(next);
        List<String> changed = next.changedSections(current);
        if (changed.isEmpty()) {
            return current;
        }
        if (!configs.insert(next, summary)) {
            throw new ConflictException("content.stale-revision", "Someone else saved at the same time; reload");
        }
        audit.record(actor.subject(), "site-config.updated", "site-config", String.valueOf(next.revision()),
                Map.of("changedSections", changed, "summary", summary == null ? "" : summary,
                        "previousRevision", current.revision()));
        events.publish(ContentEvents.SITE_CONFIG_CHANGED, ContentEvents.AGGREGATE, "site-config",
                new ContentEventPayloads.SiteConfigChanged(next.revision(), changed, actor.subject()));
        return next;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevisionSummary> revisions() {
        access.requireAny(ContentPermissions.SITE_VIEW, ContentPermissions.SITE_EDIT);
        return configs.revisions(100);
    }

    private SiteConfig latest() {
        return configs.latest().orElseThrow(() -> new NotFoundException("content.site-config-missing",
                "No site configuration has been published"));
    }
}
