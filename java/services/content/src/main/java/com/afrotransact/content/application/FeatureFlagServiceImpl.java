package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.application.FeatureFlagService.FlagChange;
import com.afrotransact.content.domain.flag.FeatureFlag;
import com.afrotransact.content.domain.flag.FlagTargeting;
import com.afrotransact.content.port.FlagRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FeatureFlagServiceImpl implements FeatureFlagService {

    private final FlagRepository flags;
    private final FlagCache cache;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final DomainEventPublisher events;
    private final Clock clock;

    FeatureFlagServiceImpl(FlagRepository flags, FlagCache cache, ContentAccess access, AuditTrail audit,
                           DomainEventPublisher events, Clock clock) {
        this.flags = flags;
        this.cache = cache;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeatureFlag> list() {
        access.requireAny(ContentPermissions.FLAGS_VIEW, ContentPermissions.FLAGS_EDIT);
        return flags.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public FeatureFlag get(String key) {
        access.requireAny(ContentPermissions.FLAGS_VIEW, ContentPermissions.FLAGS_EDIT);
        return load(key);
    }

    @Override
    @Transactional
    public FeatureFlag create(String key, String description, boolean enabled, double rolloutPercentage,
                              FlagTargeting targeting) {
        Actor actor = access.require(ContentPermissions.FLAGS_EDIT);
        FeatureFlag flag = new FeatureFlag(key, description, enabled, rolloutPercentage, false, targeting, 0,
                Instant.now(clock), actor.subject());
        flag.validate();
        if (flags.find(key).isPresent()) {
            throw new ConflictException("content.flag-exists", "Flag " + key + " already exists");
        }
        flags.insert(flag);
        return changed(actor, "flag.created", load(key));
    }

    @Override
    @Transactional
    public FeatureFlag update(String key, FlagChange change, long expectedVersion) {
        Actor actor = access.require(ContentPermissions.FLAGS_EDIT);
        FeatureFlag current = load(key);
        if (current.version() != expectedVersion) {
            throw stale(key);
        }
        FeatureFlag next = new FeatureFlag(key, change.description(), change.enabled(), change.rolloutPercentage(),
                change.killSwitch(), change.targeting(), current.version(), Instant.now(clock), actor.subject());
        next.validate();
        if (!flags.update(next)) {
            throw stale(key);
        }
        return changed(actor, "flag.updated", load(key));
    }

    @Override
    @Transactional
    public FeatureFlag killSwitch(String key, boolean on) {
        Actor actor = access.require(ContentPermissions.FLAGS_EDIT);
        FeatureFlag current = load(key);
        if (!flags.update(current.withKillSwitch(on, actor.subject(), Instant.now(clock)))) {
            throw stale(key);
        }
        return changed(actor, on ? "flag.killed" : "flag.revived", load(key));
    }

    private FeatureFlag changed(Actor actor, String action, FeatureFlag f) {
        audit.record(actor.subject(), action, "flag", f.key(), Map.of("enabled", f.enabled(),
                "rolloutPercentage", f.rolloutPercentage(), "killSwitch", f.killSwitch(), "version", f.version()));
        events.publish(ContentEvents.FLAG_CHANGED, ContentEvents.AGGREGATE, f.key(),
                new ContentEventPayloads.FlagChanged(f.key(), f.enabled(), f.killSwitch(), f.rolloutPercentage(),
                        f.version(), actor.subject()));
        cache.invalidate();
        return f;
    }

    private FeatureFlag load(String key) {
        return flags.find(key).orElseThrow(() -> new NotFoundException("content.flag-not-found", "No flag " + key));
    }

    private static ConflictException stale(String key) {
        return new ConflictException("content.concurrent-change", "Flag " + key + " was changed by someone else; reload it");
    }
}
