package com.afrotransact.trustops.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.trustops.domain.SlaPolicy;
import com.afrotransact.trustops.domain.SupportCase;
import com.afrotransact.trustops.domain.SupportCaseStatus;
import com.afrotransact.trustops.port.Page;
import com.afrotransact.trustops.port.SupportCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SupportCaseServiceImpl implements SupportCaseService {

    private final SupportCaseRepository cases;
    private final SlaPolicy sla;
    private final PermissionChecker permissions;
    private final InboxDeduplicator inbox;
    private final DomainEventPublisher events;
    private final Clock clock;

    SupportCaseServiceImpl(SupportCaseRepository cases, SlaPolicy sla, PermissionChecker permissions,
                           InboxDeduplicator inbox, DomainEventPublisher events, Clock clock) {
        this.cases = cases;
        this.sla = sla;
        this.permissions = permissions;
        this.inbox = inbox;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<SupportCaseView> openFromHandoff(UUID eventId, HandoffRequest h) {
        if (h.handoffId() == null || !inbox.firstDelivery(TrustOpsEvents.CONSUMER_HANDOFFS, eventId)
                || cases.existsByHandoff(h.handoffId())) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        SupportCase opened = cases.insert(SupportCase.fromHandoff(UUID.randomUUID(), cases.nextReference(),
                h.handoffId(), h.sessionId(), h.subject(), h.reason(), h.summary(),
                h.requestedAt() == null ? now : h.requestedAt(), sla));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", opened.id().toString());
        payload.put("reference", opened.reference());
        payload.put("handoffId", h.handoffId().toString());
        payload.put("sessionId", opened.sessionId());
        payload.put("customerId", opened.customerId());
        payload.put("reason", opened.reason());
        payload.put("dueAt", opened.sla().dueAt().toString());
        events.publish(TrustOpsEvents.SUPPORT_CASE_OPENED, TrustOpsEvents.AGGREGATE, opened.id().toString(), payload);
        return Optional.of(SupportCaseView.of(opened, now));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupportCaseView> queue(SupportCaseStatus status, int page, int size) {
        permissions.require(TrustOpsPermissions.SUPPORT);
        Instant now = Instant.now(clock);
        return cases.search(status, page, size).map(c -> SupportCaseView.of(c, now));
    }

    @Override
    @Transactional(readOnly = true)
    public SupportCaseView get(UUID id) {
        permissions.require(TrustOpsPermissions.SUPPORT);
        return SupportCaseView.of(find(id), Instant.now(clock));
    }

    @Override
    @Transactional
    public SupportCaseView assign(UUID id, String assignee) {
        Actor actor = permissions.require(TrustOpsPermissions.SUPPORT);
        SupportCase saved = cases.update(find(id).assign(assignee == null || assignee.isBlank() ? actor.subject()
                : assignee));
        return SupportCaseView.of(saved, Instant.now(clock));
    }

    @Override
    @Transactional
    public SupportCaseView resolve(UUID id, String resolution) {
        Actor actor = permissions.require(TrustOpsPermissions.SUPPORT);
        Instant now = Instant.now(clock);
        SupportCase saved = cases.update(find(id).resolve(actor.subject(), resolution, now));
        return SupportCaseView.of(saved, now);
    }

    private SupportCase find(UUID id) {
        return cases.findById(id).orElseThrow(() -> new NotFoundException("trustops.support-case-not-found",
                "Support case not found"));
    }
}
