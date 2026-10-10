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
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.trustops.domain.ModerationItem;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ModerationStatus;
import com.afrotransact.trustops.domain.ProductSubmission;
import com.afrotransact.trustops.domain.SlaPolicy;
import com.afrotransact.trustops.domain.TriageHint;
import com.afrotransact.trustops.port.CatalogModerationClient;
import com.afrotransact.trustops.port.ModerationRepository;
import com.afrotransact.trustops.port.Page;
import com.afrotransact.trustops.port.TriageClient;
import com.afrotransact.trustops.port.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ModerationServiceImpl implements ModerationService {

    private static final Logger LOG = LoggerFactory.getLogger(ModerationServiceImpl.class);

    private final ModerationRepository items;
    private final CatalogModerationClient catalog;
    private final TriageClient triage;
    private final SlaPolicy sla;
    private final PermissionChecker permissions;
    private final InboxDeduplicator inbox;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final Clock clock;

    ModerationServiceImpl(ModerationRepository items, CatalogModerationClient catalog, TriageClient triage,
                          SlaPolicy sla, PermissionChecker permissions, InboxDeduplicator inbox,
                          DomainEventPublisher events, AuditTrail audit, Clock clock) {
        this.items = items;
        this.catalog = catalog;
        this.triage = triage;
        this.sla = sla;
        this.permissions = permissions;
        this.inbox = inbox;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<ModerationItemView> enqueue(UUID eventId, ProductSubmission submission) {
        if (!inbox.firstDelivery(TrustOpsEvents.CONSUMER_PRODUCTS, eventId)
                || items.findOpenByProduct(submission.productId()).isPresent()) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        ModerationItem queued = items.insert(ModerationItem.queue(UUID.randomUUID(), submission,
                hint(submission).orElse(null), now, sla));
        publish(TrustOpsEvents.MODERATION_QUEUED, queued);
        return Optional.of(ModerationItemView.of(queued, now));
    }

    @Override
    @Transactional
    public void closeFromCatalog(UUID eventId, UUID productId, String catalogStatus) {
        if (!inbox.firstDelivery(TrustOpsEvents.CONSUMER_PRODUCTS, eventId)) {
            return;
        }
        items.findOpenByProduct(productId).ifPresent(open -> {
            ModerationItem closed = items.update(open.closeUpstream(catalogStatus, Instant.now(clock)));
            publish(TrustOpsEvents.MODERATION_DECIDED, closed);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ModerationItemView> queue(ModerationStatus status, String assignee, boolean mine, int page,
                                          int size) {
        Actor actor = permissions.require(TrustOpsPermissions.MODERATE);
        Instant now = Instant.now(clock);
        return items.search(status, mine ? actor.subject() : assignee, page, size)
                .map(i -> ModerationItemView.of(i, now));
    }

    @Override
    @Transactional(readOnly = true)
    public ModerationItemView get(UUID id) {
        permissions.require(TrustOpsPermissions.MODERATE);
        return ModerationItemView.of(find(id), Instant.now(clock));
    }

    @Override
    @Transactional
    public ModerationItemView assign(UUID id, String assignee) {
        Actor actor = permissions.require(TrustOpsPermissions.MODERATE);
        Instant now = Instant.now(clock);
        ModerationItem saved = items.update(find(id).assign(assignee == null || assignee.isBlank() ? actor.subject()
                : assignee, now));
        return ModerationItemView.of(saved, now);
    }

    @Override
    @Transactional
    public ModerationItemView escalate(UUID id, String reason) {
        Actor actor = permissions.require(TrustOpsPermissions.MODERATE);
        Instant now = Instant.now(clock);
        ModerationItem saved = items.update(find(id).escalate(reason, now, sla));
        audit.action(actor, "trustops.listing.escalated", "LISTING", saved.product().productId().toString(),
                saved.product().sellerOrgId(), "Escalated: " + saved.escalationReason(), details(saved));
        publish(TrustOpsEvents.MODERATION_ESCALATED, saved);
        return ModerationItemView.of(saved, now);
    }

    @Override
    @Transactional
    public ModerationItemView approve(UUID id, String note) {
        Actor actor = permissions.require(TrustOpsPermissions.MODERATE);
        Instant now = Instant.now(clock);
        ModerationItem decided = find(id).approve(actor.subject(), note, now);
        callCatalog(() -> catalog.approve(decided.product().productId()));
        return finish(actor, decided, "trustops.listing.approved", now);
    }

    @Override
    @Transactional
    public ModerationItemView reject(UUID id, ModerationReasonCode reasonCode, String note) {
        Actor actor = permissions.require(TrustOpsPermissions.MODERATE);
        Instant now = Instant.now(clock);
        ModerationItem decided = find(id).reject(actor.subject(), reasonCode, note, now);
        callCatalog(() -> catalog.reject(decided.product().productId(), decided.catalogNote()));
        return finish(actor, decided, "trustops.listing.rejected", now);
    }

    @Override
    @Transactional
    public ModerationItemView retriage(UUID id) {
        permissions.require(TrustOpsPermissions.MODERATE);
        Instant now = Instant.now(clock);
        ModerationItem item = find(id);
        ModerationItem updated = hint(item.product()).map(h -> items.update(item.withTriage(h, sla))).orElse(item);
        return ModerationItemView.of(updated, now);
    }

    private ModerationItemView finish(Actor actor, ModerationItem decided, String action, Instant now) {
        ModerationItem saved = items.update(decided);
        audit.action(actor, action, "LISTING", saved.product().productId().toString(), saved.product().sellerOrgId(),
                (saved.status() == ModerationStatus.APPROVED ? "Approved" : "Rejected") + " \""
                        + saved.product().title() + "\"" + (saved.reasonCode() == null ? ""
                        : " — " + saved.reasonCode().name()), details(saved));
        publish(TrustOpsEvents.MODERATION_DECIDED, saved);
        return ModerationItemView.of(saved, now);
    }

    /** AI triage is advisory and fail-open: any failure means "no hint". */
    private Optional<TriageHint> hint(ProductSubmission p) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", p.title());
        if (p.productTypeCode() != null) {
            fields.put("productType", p.productTypeCode());
        }
        if (p.ownerKind() != null) {
            fields.put("ownerKind", p.ownerKind());
        }
        fields.put("sellerOrgId", p.sellerOrgId());
        try {
            return triage.triage("LISTING", p.productId().toString(), fields);
        } catch (RuntimeException e) {
            LOG.warn("AI triage unavailable for product {}; queued without a hint: {}", p.productId(), e.getMessage());
            return Optional.empty();
        }
    }

    private static void callCatalog(Runnable call) {
        try {
            call.run();
        } catch (UpstreamException e) {
            if (e.rejected() || e.status() == 404) {
                throw new ConflictException("trustops.catalog-refused", "Catalog refused the decision: "
                        + e.getMessage());
            }
            throw e;
        }
    }

    private ModerationItem find(UUID id) {
        return items.findById(id).orElseThrow(() -> new NotFoundException("trustops.moderation-item-not-found",
                "Moderation item not found"));
    }

    private static Map<String, Object> details(ModerationItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", i.id().toString());
        m.put("productId", i.product().productId().toString());
        m.put("atin", i.product().atin());
        m.put("sellerOrgId", i.product().sellerOrgId());
        m.put("title", i.product().title());
        m.put("status", i.status().name());
        m.put("reasonCode", i.reasonCode() == null ? null : i.reasonCode().name());
        m.put("note", i.decisionNote());
        m.put("escalationReason", i.escalationReason());
        m.put("aiRiskLevel", i.triage() == null ? null : i.triage().riskLevel());
        return m;
    }

    private void publish(String type, ModerationItem i) {
        Map<String, Object> m = details(i);
        m.put("priority", i.priority().name());
        m.put("dueAt", i.sla().dueAt().toString());
        m.put("decidedBy", i.decidedBy());
        events.publish(type, TrustOpsEvents.AGGREGATE, i.product().productId().toString(), m);
    }
}
