package com.afrotransact.trustops.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.EventSerializer;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.trustops.domain.AuditDraft;
import com.afrotransact.trustops.domain.AuditEntry;
import com.afrotransact.trustops.domain.AuditFacts;
import com.afrotransact.trustops.domain.AuditHashChain;
import com.afrotransact.trustops.port.AuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to the hash-chained audit log inside the caller's transaction: takes the chain lock, links the record to
 * the current head and streams it as {@code trustops.audit.recorded}. Used for consumed events and for every
 * trust-ops decision (moderation, claims, suspensions).
 */
@Component
class AuditTrail {

    static final String OWN_SOURCE = "trust-ops";
    /** Actor of automatic steps (SLA sweeps, consumed catalog decisions). */
    static final Actor SYSTEM = new Actor("system", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of());

    private final AuditLogRepository log;
    private final AuditHashChain chain;
    private final DomainEventPublisher events;
    private final EventSerializer json;
    private final TrustOpsSettings settings;
    private final Clock clock;

    AuditTrail(AuditLogRepository log, AuditHashChain chain, DomainEventPublisher events, EventSerializer json,
               TrustOpsSettings settings, Clock clock) {
        this.log = log;
        this.chain = chain;
        this.events = events;
        this.json = json;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    AuditEntry append(AuditDraft draft) {
        log.lockChain();
        AuditEntry entry = log.last()
                .map(head -> chain.link(draft, head.seq() + 1, head.hash()))
                .orElseGet(() -> chain.link(draft, 1, AuditHashChain.GENESIS));
        log.insert(entry);
        if (settings.auditStream()) {
            AuditFacts f = entry.facts();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("seq", entry.seq());
            payload.put("id", draft.id().toString());
            payload.put("eventType", draft.eventType());
            payload.put("actorId", f.actorId());
            payload.put("actorType", f.actorType());
            payload.put("action", f.action());
            payload.put("entityType", f.entityType());
            payload.put("entityId", f.entityId());
            payload.put("orgId", f.orgId());
            payload.put("summary", f.summary());
            payload.put("occurredAt", draft.occurredAt().toString());
            payload.put("hash", entry.hash());
            payload.put("prevHash", entry.prevHash());
            events.publish(TrustOpsEvents.AUDIT_RECORDED, TrustOpsEvents.AGGREGATE, "audit-log", payload);
        }
        return entry;
    }

    /** Records a trust-ops decision made by {@code actor}. */
    @Transactional(propagation = Propagation.MANDATORY)
    AuditEntry action(Actor actor, String action, String entityType, String entityId, String orgId, String summary,
                      Map<String, Object> details) {
        Instant now = Instant.now(clock);
        AuditFacts facts = new AuditFacts(actor.subject(), actor.type().name(), action, entityType, entityId, orgId,
                summary);
        return append(new AuditDraft(UUID.randomUUID(), null, OWN_SOURCE, action, facts, json.toJson(details), now,
                now));
    }
}
