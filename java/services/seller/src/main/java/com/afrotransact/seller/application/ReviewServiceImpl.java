package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationAlreadyDecided;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.BusinessDetailsSection;
import com.afrotransact.seller.domain.DocumentAccess;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.ReasonCode;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.MediaClient;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.ReasonCodeRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
class ReviewServiceImpl implements ReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewServiceImpl.class);
    private static final int MAX_PAGE = 200;

    private final ApplicationRepository apps;
    private final OrganisationRepository orgs;
    private final ReasonCodeRepository reasons;
    private final MediaClient media;
    private final ApplicationViews views;
    private final BillingService billing;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    ReviewServiceImpl(ApplicationRepository apps, OrganisationRepository orgs, ReasonCodeRepository reasons,
                      MediaClient media, ApplicationViews views, BillingService billing, AccessGuard access,
                      DomainEventPublisher events, TransactionOperations tx, Clock clock) {
        this.apps = apps;
        this.orgs = orgs;
        this.reasons = reasons;
        this.media = media;
        this.views = views;
        this.billing = billing;
        this.access = access;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApplicationSummary> queue(List<ApplicationStatus> statuses, OrgKind kind, int page, int size) {
        access.staff(SellerPermissions.APPLICATIONS_REVIEW);
        List<ApplicationStatus> wanted = statuses == null || statuses.isEmpty()
                ? List.of(ApplicationStatus.PENDING_REVIEW, ApplicationStatus.FLAGGED,
                        ApplicationStatus.AWAITING_FINAL_APPROVAL)
                : statuses;
        Page<Application> found = apps.search(wanted, kind, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE));
        return new Page<>(found.items().stream().map(ReviewServiceImpl::summary).toList(), found.page(), found.size(),
                found.total());
    }

    @Override
    @Transactional
    public ApplicationView detail(String applicationId) {
        Actor actor = access.staff(SellerPermissions.APPLICATIONS_REVIEW);
        return staffView(find(applicationId), actor);
    }

    @Override
    @Transactional
    public DocumentView viewDocument(String applicationId, String assetId) {
        Actor actor = access.staff(SellerPermissions.APPLICATIONS_REVIEW);
        Application app = find(applicationId);
        DocumentRef doc = ApplicationViews.documents(app.data()).stream().filter(d -> d.assetId().equals(assetId))
                .findFirst().orElseThrow(() -> new NotFoundException("seller.document-not-found",
                        "Document " + assetId + " is not part of application " + app.reference()));
        apps.logAccess(new DocumentAccess(UUID.randomUUID(), app.id(), assetId, doc.kind(), actor.subject(),
                Instant.now(clock)));
        return new DocumentView(doc.assetId(), doc.kind(), doc.label(), media.viewUrl(assetId));
    }

    @Override
    @Transactional
    public ApplicationView review(String applicationId, ReviewCommand cmd) {
        Actor actor = access.staff(SellerPermissions.APPLICATIONS_REVIEW);
        ReasonCode reason = reason(cmd);
        Application app = apps.lockById(applicationId).orElseThrow(() -> notFound(applicationId));
        if (isReplay(app, cmd, actor)) {
            return staffView(app, actor);
        }
        Instant now = Instant.now(clock);
        Application next = apps.update(named(app, actor, () -> app.review(cmd.action(), actor.subject(),
                reason.code(), cmd.failedChecks(), cmd.requestedItems(), now)));
        TimelineEntry entry = timeline(app, next, cmd, actor, reason, now);
        syncOrg(next, now);
        switch (cmd.action()) {
            case FLAG -> publish(SellerEvents.APPLICATION_FLAGGED, next, entry);
            case REQUEST_INFO -> publish(SellerEvents.APPLICATION_INFO_REQUESTED, next, entry);
            default -> {
            }
        }
        return staffView(next, actor);
    }

    @Override
    public ApplicationView decide(String applicationId, ReviewCommand cmd) {
        Actor actor = access.staff(SellerPermissions.APPLICATIONS_APPROVE);
        ReasonCode reason = tx.execute(s -> reason(cmd));
        Decided decided = tx.execute(s -> decideInTx(applicationId, cmd, actor, reason));
        if (!decided.replayed() && decided.application().isApproved()) {
            try {
                billing.activate(decided.application().orgId());
            } catch (RuntimeException e) {
                LOG.warn("Subscription activation for {} failed; the billing job will retry",
                        decided.application().orgId(), e);
            }
        }
        return tx.execute(s -> staffView(decided.application(), actor));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReasonCode> reasonCodes() {
        access.staff(SellerPermissions.APPLICATIONS_REVIEW);
        return reasons.findAll();
    }

    private Decided decideInTx(String applicationId, ReviewCommand cmd, Actor actor, ReasonCode reason) {
        Application app = apps.lockById(applicationId).orElseThrow(() -> notFound(applicationId));
        if (isReplay(app, cmd, actor)) {
            return new Decided(app, true);
        }
        Instant now = Instant.now(clock);
        Application next = apps.update(named(app, actor, () -> app.decide(cmd.action(), actor.subject(),
                reason.code(), cmd.requestedItems(), now)));
        TimelineEntry entry = timeline(app, next, cmd, actor, reason, now);
        Organisation org = syncOrg(next, now);
        switch (cmd.action()) {
            case FINAL_APPROVE -> {
                publish(SellerEvents.APPLICATION_APPROVED, next, entry);
                List<String> owners = orgs.members(org.id()).stream().filter(OrgMember::isOwner)
                        .map(OrgMember::userId).toList();
                OrgEventPayload payload = OrgEventPayload.of(org, owners, next.applicantUserId(), now);
                events.publish(SellerEvents.ORG_APPROVED, SellerEvents.AGGREGATE_ORG, org.id(), payload);
                events.publish(SellerEvents.ORG_UPDATED, SellerEvents.AGGREGATE_ORG, org.id(), payload);
            }
            case REJECT -> publish(SellerEvents.APPLICATION_REJECTED, next, entry);
            case REQUEST_INFO -> publish(SellerEvents.APPLICATION_INFO_REQUESTED, next, entry);
            default -> {
            }
        }
        return new Decided(next, false);
    }

    /**
     * A decision that already happened is answered with the current state and changes nothing (no timeline entry,
     * no events, no email): a retry with the same Idempotency-Key, or the same person repeating the decision that put
     * the application in its current state (a double submit, a retried request without a key).
     */
    private boolean isReplay(Application app, ReviewCommand cmd, Actor actor) {
        if (cmd.requestKey() != null) {
            Optional<TimelineEntry> first = apps.timelineEntry(app.id(), cmd.requestKey());
            if (first.isPresent()) {
                if (first.get().action() != cmd.action() || !actor.subject().equals(first.get().actorId())) {
                    throw new SellerStateConflict("seller.idempotency-key-reused",
                            "This Idempotency-Key was already used for a different decision");
                }
                LOG.info("Replayed {} on {} (Idempotency-Key)", cmd.action(), app.reference());
                return true;
            }
        }
        List<TimelineEntry> timeline = apps.timeline(app.id());
        if (app.isRepeatOf(cmd.action(), actor.subject(), timeline.isEmpty() ? null : timeline.getLast())) {
            LOG.info("Replayed {} on {}: {} repeated their own decision", cmd.action(), app.reference(),
                    actor.subject());
            return true;
        }
        return false;
    }

    /** Runs a review step; an "already decided" conflict is re-thrown naming who decided (from the timeline). */
    private Application named(Application app, Actor actor, Supplier<Application> step) {
        try {
            return step.get();
        } catch (ApplicationAlreadyDecided e) {
            String name = apps.timeline(app.id()).reversed().stream()
                    .filter(t -> t.toStatus() == e.status() && t.actorId() != null
                            && t.actorId().equals(e.deciderId()))
                    .map(TimelineEntry::actorName).filter(Objects::nonNull).findFirst().orElse(null);
            throw e.named(name, actor.subject());
        }
    }

    /** The application after a final decision, and whether the decision was a replay (nothing changed). */
    private record Decided(Application application, boolean replayed) {
    }

    /** Mirrors the application status on the organisation; on approval also sets its legal and display names. */
    private Organisation syncOrg(Application app, Instant now) {
        Organisation org = orgs.findById(app.orgId()).orElseThrow(() -> new NotFoundException("seller.org-not-found",
                "Organisation " + app.orgId() + " not found"));
        Organisation next = org.withStatus(OrgStatus.of(app.status()), now);
        if (app.isApproved()) {
            BusinessDetailsSection d = app.data().businessDetails();
            String legal = d == null ? null : d.legalName() != null ? d.legalName()
                    : (nz(d.legalFirstName()) + " " + nz(d.legalLastName())).trim();
            String display = app.data().business() == null ? null : app.data().business().storeName();
            String country = app.data().business() == null ? null : app.data().business().country();
            next = next.withProfile(legal, display == null ? legal : display, country, now);
            if (app.data().business() != null && app.data().business().offering() != null) {
                next = next.withOffering(app.data().business().offering(), now);
            }
        }
        return orgs.update(next);
    }

    private TimelineEntry timeline(Application before, Application after, ReviewCommand cmd, Actor actor,
                                   ReasonCode reason, Instant now) {
        List<String> items = switch (cmd.action()) {
            case FLAG -> cmd.failedChecks();
            case REQUEST_INFO -> cmd.requestedItems();
            default -> List.of();
        };
        TimelineEntry entry = new TimelineEntry(UUID.randomUUID(), before.id(), cmd.action(), before.status(),
                after.status(), actor.subject(), reason.code(), cmd.note(), items, now, cmd.actorName(),
                cmd.requestKey());
        apps.appendTimeline(entry);
        return entry;
    }

    /** Dossier for staff; unmasked PII (applications:pii) is written to the access log every time it is shown. */
    private ApplicationView staffView(Application app, Actor actor) {
        boolean pii = actor.can(SellerPermissions.APPLICATIONS_PII);
        if (pii) {
            apps.logAccess(new DocumentAccess(UUID.randomUUID(), app.id(), null, "PII_VIEW", actor.subject(),
                    Instant.now(clock)));
        }
        return views.view(app, pii ? PiiLevel.FULL : PiiLevel.MASKED, apps.timeline(app.id()),
                apps.accessLog(app.id()));
    }

    private ReasonCode reason(ReviewCommand cmd) {
        if (cmd.action() == null) {
            throw new SellerRuleViolation("seller.invalid-action", "Choose an action",
                    Map.of("action", "Required"));
        }
        if (cmd.reasonCode() == null || cmd.reasonCode().isBlank()) {
            throw new SellerRuleViolation("seller.reason-code-required", "Pick a reason code",
                    Map.of("reasonCode", "Required"));
        }
        ReasonCode found = reasons.find(cmd.reasonCode().trim()).orElseThrow(() -> new SellerRuleViolation(
                "seller.unknown-reason-code", "Unknown reason code " + cmd.reasonCode(),
                Map.of("reasonCode", "Unknown")));
        if (!appliesTo(found.appliesTo(), cmd.action())) {
            throw new SellerRuleViolation("seller.reason-code-not-applicable",
                    "That reason does not fit this decision", Map.of("reasonCode", "Choose a reason that fits"));
        }
        return found;
    }

    /** A reason seeded for APPROVE fits approvals only, one for REJECT fits rejections only, ANY fits every decision. */
    private static boolean appliesTo(String appliesTo, ReviewAction action) {
        if (appliesTo == null || "ANY".equals(appliesTo)) {
            return true;
        }
        boolean approval = action == ReviewAction.APPROVE || action == ReviewAction.FINAL_APPROVE;
        return "APPROVE".equals(appliesTo) ? approval : action == ReviewAction.REJECT;
    }

    private void publish(String type, Application app, TimelineEntry entry) {
        events.publish(type, SellerEvents.AGGREGATE_APPLICATION, app.id(), ApplicationEventPayload.of(app, entry));
    }

    private Application find(String id) {
        return apps.findById(id).orElseThrow(() -> notFound(id));
    }

    private static ApplicationSummary summary(Application a) {
        int step = switch (a.status()) {
            case PENDING_REVIEW -> 1;
            case FLAGGED, AWAITING_FINAL_APPROVAL -> 2;
            default -> 0;
        };
        var b = a.data().business();
        var d = a.data().businessDetails();
        return new ApplicationSummary(a.id(), a.reference(), a.orgId(), a.kind().name(), a.status().name(),
                b == null ? null : b.storeName(),
                d == null ? null : d.legalName() != null ? d.legalName()
                        : (nz(d.legalFirstName()) + " " + nz(d.legalLastName())).trim(),
                d == null || d.address() == null ? d == null ? null : d.stateOfFormation() : d.address().state(),
                a.data().plan() == null ? null : a.data().plan().planCode(), step, a.reviewerId(), a.failedChecks(),
                a.submittedAt(), a.updatedAt());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("seller.application-not-found", "Application " + id + " not found");
    }
}
