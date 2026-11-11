package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.application.SellingViews.SellingApprovalView;
import com.afrotransact.seller.application.SellingViews.SellingCaseView;
import com.afrotransact.seller.application.SellingViews.SellingDocumentOnFile;
import com.afrotransact.seller.application.SellingViews.SellingDocumentSlot;
import com.afrotransact.seller.application.SellingViews.SellingCaseSummary;
import com.afrotransact.seller.domain.ApplicationCase;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.AutoAssignMode;
import com.afrotransact.seller.domain.DocumentAccess;
import com.afrotransact.seller.domain.EscalationTarget;
import com.afrotransact.seller.domain.ReasonCode;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.ReviewSettings;
import com.afrotransact.seller.domain.ReviewStage;
import com.afrotransact.seller.domain.SellerForbidden;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.SellingApplication;
import com.afrotransact.seller.domain.SellingAssignmentRules;
import com.afrotransact.seller.domain.SellingDocuments;
import com.afrotransact.seller.domain.SlaState;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.port.CallerProfileProvider;
import com.afrotransact.seller.port.CaseSearch;
import com.afrotransact.seller.port.MediaClient;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.ReasonCodeRepository;
import com.afrotransact.seller.port.ReviewSettingsRepository;
import com.afrotransact.seller.port.SellingApplicationRepository;
import com.afrotransact.seller.port.SellingApprovalRepository;
import com.afrotransact.seller.port.SellingCaseRepository;
import com.afrotransact.seller.port.SellingCaseRow;
import com.afrotransact.seller.port.SellingRuleRepository;
import com.afrotransact.seller.port.StaffDirectory;
import com.afrotransact.seller.port.StaffDirectory.StaffPerson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
class SellingReviewServiceImpl implements SellingReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(SellingReviewServiceImpl.class);
    private static final String SYSTEM = "system";
    private static final String UNKNOWN = "Unknown user";
    private static final String KIND = "SELLING_APPROVAL";
    private static final int MAX_PAGE = 200;
    private static final int BATCH = 100;

    private final SellingApplicationRepository apps;
    private final SellingCaseRepository cases;
    private final SellingApprovalRepository approvals;
    private final SellingRuleRepository rules;
    private final SellingApprovalService approvalService;
    private final ReviewSettingsRepository settingsRepo;
    private final ReasonCodeRepository reasons;
    private final StaffDirectory directory;
    private final MediaClient media;
    private final AccessGuard access;
    private final CallerProfileProvider profiles;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    SellingReviewServiceImpl(SellingApplicationRepository apps, SellingCaseRepository cases,
                             SellingApprovalRepository approvals, SellingRuleRepository rules,
                             SellingApprovalService approvalService, ReviewSettingsRepository settingsRepo,
                             ReasonCodeRepository reasons, StaffDirectory directory, MediaClient media,
                             AccessGuard access, CallerProfileProvider profiles, DomainEventPublisher events,
                             TransactionOperations tx, Clock clock) {
        this.apps = apps;
        this.cases = cases;
        this.approvals = approvals;
        this.rules = rules;
        this.approvalService = approvalService;
        this.settingsRepo = settingsRepo;
        this.reasons = reasons;
        this.directory = directory;
        this.media = media;
        this.access = access;
        this.profiles = profiles;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    @Override
    @Transactional(readOnly = true)
    public Page<SellingCaseSummary> queue(QueueRequest q) {
        Actor actor = viewer();
        ReviewSettings settings = settingsRepo.load();
        Instant now = Instant.now(clock);
        String assignee = "me".equals(q.assignee()) ? actor.subject() : q.assignee();
        CaseSearch search = new CaseSearch(q.queue(), q.statuses(), null, q.stage(), assignee, q.unassigned(),
                q.overdue(), q.escalated(), actor.subject(), actor.can(SellerPermissions.APPLICATIONS_REVIEW),
                actor.can(SellerPermissions.APPLICATIONS_APPROVE), now.minus(settings.sla(ReviewStage.REVIEW)),
                now.minus(settings.sla(ReviewStage.APPROVAL)), Math.max(q.page(), 0),
                Math.min(Math.max(q.size(), 1), MAX_PAGE));
        Page<SellingCaseRow> found = apps.searchCases(search);
        return new Page<>(found.items().stream().map(r -> summary(r.application(),
                viewFor(r.application(), r.workflow(), actor, settings))).toList(), found.page(), found.size(),
                found.total());
    }

    @Override
    @Transactional(readOnly = true)
    public SellingCaseView detail(String id) {
        Actor actor = viewer();
        return caseView(find(id), actor);
    }

    @Override
    @Transactional
    public DocumentView viewDocument(String id, String assetId) {
        Actor actor = access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE);
        SellingApplication app = find(id);
        var doc = app.documents().stream().filter(d -> d.assetId() != null && d.assetId().equals(assetId))
                .findFirst().orElseThrow(() -> new NotFoundException("seller.document-not-found",
                        "Document " + assetId + " is not part of application " + app.reference()));
        apps.logAccess(new DocumentAccess(UUID.randomUUID(), app.id(), assetId, doc.kind().name(), actor.subject(),
                Instant.now(clock)));
        return new DocumentView(doc.assetId(), doc.kind().name(), doc.kind().label(), media.viewUrl(assetId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReasonCode> reasonCodes() {
        access.staff(SellerPermissions.APPLICATIONS_REVIEW);
        return reasons.findAll();
    }

    // ---------------------------------------------------------------- decisions

    @Override
    @Transactional
    public SellingCaseView review(String id, ReviewCommand cmd) {
        Actor actor = access.staff(SellerPermissions.APPLICATIONS_REVIEW);
        ReasonCode reason = reason(cmd);
        SellingApplication app = apps.lockById(id).orElseThrow(() -> notFound(id));
        if (isReplay(app, cmd, actor)) {
            return caseView(app, actor);
        }
        enforceAssignee(app, actor);
        if (cmd.action() == ReviewAction.APPROVE) {
            requireDocuments(app);
        }
        Instant now = Instant.now(clock);
        SellingApplication next = apps.update(app.review(cmd.action(), actor.subject(), reason.code(),
                cmd.failedChecks(), cmd.requestedItems(), cmd.note(), now));
        timeline(app, next, cmd, actor, reason, now);
        transitioned(app, next, cmd.action(), actor.subject(), cmd.actorName(), cmd.note());
        if (cmd.action() == ReviewAction.REQUEST_INFO) {
            approvalService.recordInfoRequested(next);
        }
        return caseView(next, actor);
    }

    @Override
    @Transactional
    public SellingCaseView decide(String id, ReviewCommand cmd, LocalDate reapplyAfter) {
        Actor actor = access.staff(SellerPermissions.APPLICATIONS_APPROVE);
        ReasonCode reason = reason(cmd);
        SellingApplication app = apps.lockById(id).orElseThrow(() -> notFound(id));
        if (isReplay(app, cmd, actor)) {
            return caseView(app, actor);
        }
        enforceAssignee(app, actor);
        if (cmd.action() == ReviewAction.DISQUALIFY && (cmd.note() == null || cmd.note().isBlank())) {
            throw new SellerRuleViolation("seller.note-required", "Tell the seller why they cannot apply",
                    Map.of("note", "Required"));
        }
        if (cmd.action() == ReviewAction.FINAL_APPROVE) {
            requireDocuments(app);
        }
        Instant now = Instant.now(clock);
        SellingApplication next = apps.update(app.decide(cmd.action(), actor.subject(), reason.code(),
                cmd.requestedItems(), cmd.note(), reapplyAfter, now));
        timeline(app, next, cmd, actor, reason, now);
        transitioned(app, next, cmd.action(), actor.subject(), cmd.actorName(), cmd.note());
        switch (cmd.action()) {
            case FINAL_APPROVE -> approvalService.recordApproved(next);
            case REJECT, DISQUALIFY -> approvalService.recordRejected(next);
            case REQUEST_INFO -> approvalService.recordInfoRequested(next);
            default -> { }
        }
        return caseView(next, actor);
    }

    /** An approval needs every required document on file and none expired (a migrated application starts with none). */
    private void requireDocuments(SellingApplication app) {
        LocalDate submitted = (app.submittedAt() == null ? Instant.now(clock) : app.submittedAt())
                .atZone(ZoneOffset.UTC).toLocalDate();
        rules.find(app.scope()).ifPresent(rule -> SellingDocuments.require(rule.documents(), app.documents(),
                submitted));
        LocalDate today = Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate();
        if (app.documents().stream().anyMatch(d -> d.expiresOn() != null && !d.expiresOn().isAfter(today))) {
            throw new SellerRuleViolation("seller.selling-documents-invalid",
                    "A document has expired since it was uploaded: ask the seller for a current one",
                    Map.of("documents", "Expired"));
        }
    }

    private boolean isReplay(SellingApplication app, ReviewCommand cmd, Actor actor) {
        if (cmd.requestKey() != null) {
            Optional<TimelineEntry> first = apps.timelineEntry(app.id(), cmd.requestKey());
            if (first.isPresent()) {
                if (first.get().action() != cmd.action() || !actor.subject().equals(first.get().actorId())) {
                    throw new SellerStateConflict("seller.idempotency-key-reused",
                            "This Idempotency-Key was already used for a different decision");
                }
                return true;
            }
        }
        TimelineEntry last = apps.timeline(app.id()).reversed().stream().filter(t -> !t.action().isWorkflow())
                .findFirst().orElse(null);
        if (last == null || !last.decided(cmd.action(), actor.subject(), app.status())) {
            return false;
        }
        return switch (cmd.action()) {
            case FINAL_APPROVE, REJECT, DISQUALIFY -> actor.subject().equals(app.finalApproverId());
            case APPROVE, FLAG, REQUEST_INFO -> actor.subject().equals(app.reviewerId());
            case SEND_BACK -> app.status() == ApplicationStatus.PENDING_REVIEW;
            default -> false;
        };
    }

    private void enforceAssignee(SellingApplication app, Actor actor) {
        if (ReviewStage.of(app.status()).isEmpty() || actor.can(SellerPermissions.APPLICATIONS_ASSIGN)) {
            return;
        }
        ApplicationCase c = cases.find(app.id()).orElse(null);
        if (c != null && c.assigned() && !actor.subject().equals(c.assigneeId())
                && settingsRepo.load().enforceAssignee()) {
            throw new SellerForbidden("seller.assigned-to-other", app.reference() + " is with " + c.assigneeName()
                    + ". Ask a supervisor to reassign it, or ask " + c.assigneeName() + " to release it.");
        }
    }

    private void timeline(SellingApplication before, SellingApplication after, ReviewCommand cmd, Actor actor,
                          ReasonCode reason, Instant now) {
        List<String> items = switch (cmd.action()) {
            case FLAG -> cmd.failedChecks();
            case REQUEST_INFO -> cmd.requestedItems();
            default -> List.of();
        };
        apps.appendTimeline(new TimelineEntry(UUID.randomUUID(), before.id(), cmd.action(), before.status(),
                after.status(), actor.subject(), reason.code(), cmd.note(), items, now, cmd.actorName(),
                cmd.requestKey()));
    }

    private ReasonCode reason(ReviewCommand cmd) {
        if (cmd.action() == null) {
            throw new SellerRuleViolation("seller.invalid-action", "Choose an action", Map.of("action", "Required"));
        }
        if (cmd.reasonCode() == null || cmd.reasonCode().isBlank()) {
            throw new SellerRuleViolation("seller.reason-code-required", "Pick a reason code",
                    Map.of("reasonCode", "Required"));
        }
        ReasonCode found = reasons.find(cmd.reasonCode().trim()).orElseThrow(() -> new SellerRuleViolation(
                "seller.unknown-reason-code", "Unknown reason code " + cmd.reasonCode(),
                Map.of("reasonCode", "Unknown")));
        boolean approval = cmd.action() == ReviewAction.APPROVE || cmd.action() == ReviewAction.FINAL_APPROVE;
        boolean fits = found.appliesTo() == null || "ANY".equals(found.appliesTo())
                || ("APPROVE".equals(found.appliesTo()) ? approval : !approval);
        if (!fits) {
            throw new SellerRuleViolation("seller.reason-code-not-applicable",
                    "That reason does not fit this decision", Map.of("reasonCode", "Choose a reason that fits"));
        }
        return found;
    }

    // ---------------------------------------------------------------- who has it

    @Override
    @Transactional
    public SellingCaseView claim(String id, String actorName) {
        Actor actor = access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE);
        SellingApplication app = lock(id);
        ReviewStage stage = stageOf(app);
        ApplicationCase c = current(app);
        if (actor.subject().equals(c.assigneeId())) {
            return caseView(app, actor);
        }
        if (c.assigned()) {
            throw new SellerStateConflict("seller.already-assigned", app.reference() + " is already with "
                    + nameOr(c.assigneeName()) + ". A supervisor can reassign it.");
        }
        SellingAssignmentRules.require(app, stage, actor.subject(), actor.can(stage.permission()), "You");
        String name = displayName(actorName);
        give(app, c, actor.subject(), name, actor.subject(), name, "Claimed by " + name);
        return caseView(app, actor);
    }

    @Override
    @Transactional
    public SellingCaseView assign(String id, String assigneeId, String note, String actorName) {
        Actor actor = access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_ASSIGN);
        if (assigneeId == null || assigneeId.isBlank()) {
            throw new SellerRuleViolation("seller.assignee-required", "Choose who to assign it to",
                    Map.of("assigneeId", "Required"));
        }
        SellingApplication app = lock(id);
        ReviewStage stage = stageOf(app);
        ApplicationCase c = current(app);
        requireManager(actor, stage, c);
        String assignee = assigneeId.trim();
        if (assignee.equals(c.assigneeId())) {
            return caseView(app, actor);
        }
        StaffPerson person = eligiblePerson(app, stage, assignee);
        String by = displayName(actorName);
        String text = (assignee.equals(actor.subject()) ? "Taken over by " + person.name() : "Assigned to "
                + person.name()) + (c.assigned() ? " (was with " + nameOr(c.assigneeName()) + ")" : "")
                + (note == null || note.isBlank() ? "" : ": " + note.trim());
        ApplicationCase next = give(app, c, person.id(), person.name(), actor.subject(), by, text);
        notifyAssigned(app, next, "ASSIGNED", by, note);
        return caseView(app, actor);
    }

    @Override
    @Transactional
    public SellingCaseView release(String id, String note, String actorName) {
        Actor actor = access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_ASSIGN);
        SellingApplication app = lock(id);
        ReviewStage stage = stageOf(app);
        ApplicationCase c = current(app);
        if (!c.assigned()) {
            return caseView(app, actor);
        }
        if (!actor.subject().equals(c.assigneeId())) {
            requireManager(actor, stage, c);
        }
        Instant now = Instant.now(clock);
        String by = displayName(actorName);
        cases.save(c.release(now));
        workflowEntry(app, ReviewAction.RELEASED, actor.subject(), by, "Released by " + by + " (was with "
                + nameOr(c.assigneeName()) + "); back to the pool: awaiting " + stage.noun() + " from any "
                + stage.person() + (note == null || note.isBlank() ? "" : ". " + note.trim()), now);
        return caseView(app, actor);
    }

    @Override
    @Transactional
    public SellingCaseView escalate(String id, String reason, String actorName) {
        Actor actor = access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_ASSIGN);
        if (reason == null || reason.isBlank()) {
            throw new SellerRuleViolation("seller.reason-required", "Say why you are escalating this case",
                    Map.of("reason", "Required"));
        }
        SellingApplication app = lock(id);
        ReviewStage stage = stageOf(app);
        ApplicationCase c = current(app);
        boolean holder = actor.subject().equals(c.assigneeId());
        if (!holder && !actor.can(SellerPermissions.APPLICATIONS_ASSIGN)) {
            throw new SellerForbidden("seller.escalate-not-allowed",
                    "Only the person who has this case or a supervisor can escalate it");
        }
        if (c.escalationLevel() >= 2) {
            throw new SellerStateConflict("seller.already-escalated",
                    app.reference() + " is already at the highest escalation level");
        }
        int level = c.escalationLevel() + 1;
        raise(app, c, stage, level, reason.trim(), resolveTargets(app, settingsRepo.load().chain(stage).at(level)),
                displayName(actorName), actor.subject(), Instant.now(clock));
        return caseView(app, actor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffPerson> candidates(String id) {
        Actor actor = viewer();
        SellingApplication app = apps.findById(id).orElseThrow(() -> notFound(id));
        Optional<ReviewStage> stage = ReviewStage.of(app.status());
        if (stage.isEmpty() || !canManage(actor, stage.get(), cases.find(app.id()).orElse(null))) {
            return List.of();
        }
        return eligiblePeople(app, stage.get());
    }

    // ---------------------------------------------------------------- transitions

    @Override
    public void opened(SellingApplication app) {
        transitioned(null, app, ReviewAction.SUBMITTED, app.applicantUserId(), app.applicantName(), null);
    }

    private void transitioned(SellingApplication before, SellingApplication after, ReviewAction cause,
                              String actorId, String actorName, String note) {
        Instant now = Instant.now(clock);
        Optional<ReviewStage> next = ReviewStage.of(after.status());
        ApplicationCase c = cases.find(after.id()).orElse(ApplicationCase.idle(after.id(), now))
                .enter(next.orElse(null), now);
        if (cause == ReviewAction.SEND_BACK) {
            c = c.returned(actorId, nameOr(actorName), note, now);
        } else if (next.isPresent() && next.get() == ReviewStage.APPROVAL || next.isEmpty()) {
            c = c.withoutReturn(now);
        }
        c = cases.save(c);
        if (next.isEmpty()) {
            return;
        }
        ReviewSettings s = settingsRepo.load();
        ReviewStage stage = next.get();
        String reviewerBefore = before == null ? null : before.reviewerId();
        boolean assigned = false;
        if (cause == ReviewAction.SEND_BACK && s.sendBackToReviewer() && reviewerBefore != null) {
            Optional<StaffPerson> original = safe(() -> eligiblePeople(after, stage)).orElse(List.of()).stream()
                    .filter(p -> p.id().equals(reviewerBefore)).findFirst();
            if (original.isPresent()) {
                c = give(after, c, original.get().id(), original.get().name(), SYSTEM, "Sent back to the original reviewer",
                        "Assigned to " + original.get().name() + " (the original reviewer: the case was sent back)");
                notifyAssigned(after, c, "SENT_BACK", nameOr(actorName), note);
                assigned = true;
            }
        }
        if (!assigned) {
            assigned = autoAssign(after, c, stage, s, cause);
        }
        if (!assigned) {
            notifyPool(after, stage, cause, actorName, note, reviewerBefore);
        }
    }

    private boolean autoAssign(SellingApplication app, ApplicationCase c, ReviewStage stage, ReviewSettings s,
                               ReviewAction cause) {
        if (s.autoAssign() == AutoAssignMode.NONE) {
            return false;
        }
        Optional<List<StaffPerson>> pool = safe(() -> eligiblePeople(app, stage));
        if (pool.isEmpty() || pool.get().isEmpty()) {
            return false;
        }
        List<StaffPerson> people = pool.get().stream().sorted(Comparator.comparing(StaffPerson::id)).toList();
        StaffPerson pick;
        if (s.autoAssign() == AutoAssignMode.LEAST_LOADED) {
            Map<String, Integer> load = cases.openLoad(stage);
            pick = people.stream().min(Comparator.<StaffPerson>comparingInt(p -> load.getOrDefault(p.id(), 0))
                    .thenComparing(StaffPerson::id)).orElseThrow();
        } else {
            ReviewSettings locked = settingsRepo.lock();
            String last = locked.roundRobinCursor().get(stage);
            pick = people.stream().filter(p -> last == null || p.id().compareTo(last) > 0).findFirst()
                    .orElse(people.getFirst());
            settingsRepo.save(locked.withCursor(stage, pick.id()));
        }
        ApplicationCase next = give(app, c, pick.id(), pick.name(), SYSTEM, "Auto-assignment", "Assigned to "
                + pick.name() + " (auto-assignment: " + s.autoAssign().name().toLowerCase().replace('_', ' ') + ")");
        String reason = stage == ReviewStage.APPROVAL ? "READY_FOR_APPROVAL"
                : cause == ReviewAction.SEND_BACK ? "SENT_BACK" : "AUTO_ASSIGNED";
        notifyAssigned(app, next, reason, "Auto-assignment", null);
        return true;
    }

    private void notifyPool(SellingApplication app, ReviewStage stage, ReviewAction cause, String actorName,
                            String note, String previousReviewerId) {
        String reason;
        if (stage == ReviewStage.APPROVAL) {
            reason = "READY_FOR_APPROVAL";
        } else if (cause == ReviewAction.SEND_BACK) {
            reason = "SENT_BACK";
        } else {
            return;
        }
        List<String> recipients = new ArrayList<>(safe(() -> eligiblePeople(app, stage)).orElse(List.of()).stream()
                .map(StaffPerson::id).toList());
        if (reason.equals("SENT_BACK") && previousReviewerId != null && !recipients.contains(previousReviewerId)
                && !previousReviewerId.equals(app.applicantUserId())) {
            recipients.add(previousReviewerId);
        }
        if (recipients.isEmpty()) {
            return;
        }
        events.publish(SellerEvents.APPLICATION_ASSIGNED, SellerEvents.AGGREGATE_APPLICATION, app.id(),
                new AssignmentEventPayload(app.id(), app.reference(), app.scopeLabel(), KIND, stage.name(), reason,
                        null, null, nameOr(actorName), recipients, 0, note, Instant.now(clock)));
    }

    // ---------------------------------------------------------------- escalation

    @Override
    @Transactional
    public boolean escalateIfDue(String id, Instant now) {
        SellingApplication app = apps.lockById(id).orElse(null);
        Optional<ReviewStage> found = app == null ? Optional.empty() : ReviewStage.of(app.status());
        if (found.isEmpty()) {
            return false;
        }
        ReviewStage stage = found.get();
        ApplicationCase c = current(app);
        ReviewSettings s = settingsRepo.load();
        Duration age = Duration.between(c.stageEnteredAt(), now);
        Duration sla = s.sla(stage);
        boolean overdue = age.compareTo(sla) >= 0;
        boolean untaken = !c.assigned() && age.compareTo(Duration.ofHours(s.unassignedAfterHours())) >= 0;
        int want = age.compareTo(sla.plusHours(s.level2AfterHours())) >= 0 ? 2 : overdue || untaken ? 1 : 0;
        if (want <= c.escalationLevel()) {
            return false;
        }
        int level = c.escalationLevel() + 1;
        List<StaffPerson> targets = resolveTargets(app, s.chain(stage).at(level));
        if (targets.isEmpty()) {
            return false;
        }
        String reason = overdue ? "Overdue: waiting " + age.toHours() + " h at " + stage.noun()
                + " (service level " + sla.toHours() + " h)" : "Nobody has taken it for " + age.toHours() + " h";
        raise(app, c, stage, level, reason, targets, "Escalation", SYSTEM, now);
        return true;
    }

    @Override
    public int runDueEscalations() {
        Instant now = Instant.now(clock);
        int raised = 0;
        for (String id : cases.escalationCandidates(BATCH)) {
            try {
                if (Boolean.TRUE.equals(tx.execute(s -> escalateIfDue(id, now)))) {
                    raised++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Escalation check of selling application {} failed; it is retried on the next run", id, e);
            }
        }
        return raised;
    }

    private ApplicationCase raise(SellingApplication app, ApplicationCase c, ReviewStage stage, int level,
                                  String reason, List<StaffPerson> targets, String byName, String byId, Instant now) {
        if (level <= c.escalationLevel()) {
            return c;
        }
        ApplicationCase next = cases.save(c.escalate(level, reason, targets.stream().map(StaffPerson::id).toList(),
                targets.stream().map(StaffPerson::name).toList(), now));
        String names = targets.isEmpty() ? "no one is set up for this level yet"
                : String.join(", ", targets.stream().map(StaffPerson::name).toList());
        workflowEntry(app, ReviewAction.ESCALATED, byId, byName, "Escalated to level " + level + " (" + names + "): "
                + reason, now);
        if (!targets.isEmpty()) {
            events.publish(SellerEvents.APPLICATION_ESCALATED, SellerEvents.AGGREGATE_APPLICATION, app.id(),
                    new AssignmentEventPayload(app.id(), app.reference(), app.scopeLabel(), KIND, stage.name(),
                            "ESCALATED", c.assigneeId(), c.assigneeName(), byName,
                            targets.stream().map(StaffPerson::id).toList(), level, reason, now));
        }
        return next;
    }

    private List<StaffPerson> resolveTargets(SellingApplication app, EscalationTarget target) {
        Map<String, StaffPerson> out = new LinkedHashMap<>();
        if (target.roleKey() != null) {
            safe(() -> directory.withRole(target.roleKey())).orElse(List.of()).forEach(p -> out.put(p.id(), p));
        }
        if (!target.userIds().isEmpty()) {
            safe(() -> directory.people(target.userIds())).orElse(List.of()).forEach(p -> out.put(p.id(), p));
        }
        out.remove(app.applicantUserId());
        return List.copyOf(out.values());
    }

    // ---------------------------------------------------------------- views

    private ApplicationCaseView viewFor(SellingApplication app, ApplicationCase workflow, Actor actor,
                                        ReviewSettings settings) {
        Instant now = Instant.now(clock);
        Optional<ReviewStage> stage = ReviewStage.of(app.status());
        ApplicationCase c = workflow == null ? ApplicationCase.idle(app.id(), app.updatedAt()) : workflow;
        ApplicationCase held = workflow == null && stage.isPresent() ? c.enter(stage.get(), app.updatedAt()) : c;
        ApplicationCase shown = stage.isPresent() && held.stage() != stage.get()
                ? held.enter(stage.get(), held.stageEnteredAt()) : held;
        boolean any = stage.isPresent();
        String holderText = !any ? null : shown.assigned() ? "with " + shown.assigneeName()
                : "any " + stage.get().person();
        long waiting = any ? Math.max(0, Duration.between(shown.stageEnteredAt(), now).getSeconds()) : 0;
        Duration allowed = any ? settings.sla(stage.get()) : null;
        String sla = any ? SlaState.of(shown.stageEnteredAt(), now, allowed, settings.dueSoonPercent()).name() : null;
        Instant due = any ? shown.stageEnteredAt().plus(allowed) : null;
        boolean mine = any && actor.subject().equals(shown.assigneeId());
        boolean eligible = any && SellingAssignmentRules.eligible(app, stage.get(), actor.subject(),
                actor.can(stage.get().permission()));
        boolean manage = any && canManage(actor, stage.get(), shown);
        return new ApplicationCaseView(any ? stage.get().name() : null, holderText, shown.assigneeId(),
                shown.assigneeName(), shown.assignedAt(), shown.assignedByName(), any ? shown.stageEnteredAt() : null,
                waiting, sla, due, any ? shown.escalationLevel() : 0, shown.escalatedAt(), shown.escalationReason(),
                shown.escalationTargetNames(), shown.returnedByName(), shown.returnedAt(), shown.returnNote(),
                shown.returnCount(), mine, any && !shown.assigned() && eligible, manage,
                any && shown.assigned() && (mine || manage), any && shown.escalationLevel() < 2
                && (mine || actor.can(SellerPermissions.APPLICATIONS_ASSIGN)),
                any && shown.assigned() && !mine && manage && eligible);
    }

    private SellingCaseView caseView(SellingApplication app, Actor actor) {
        List<SellingDocumentSlot> required = rules.find(app.scope()).map(r -> r.documents().stream().map(SellingDocumentSlot::of).toList())
                .orElse(List.of());
        SellingApprovalView approval = approvals.find(app.orgId(), app.scope())
                .map(a -> SellingApprovalView.of(a, Instant.now(clock))).orElse(null);
        return new SellingCaseView(app.id(), app.reference(), app.orgId(), app.applicantName(), app.scope().type().name(),
                app.scope().key(), app.scopeLabel(), app.kind(), app.source(), app.status().name(), app.note(),
                app.documents().stream().map(SellingDocumentOnFile::of).toList(), required, app.reviewerId(), app.reviewedAt(),
                app.finalApproverId(), app.decidedAt(), app.failedChecks(), app.requestedItems(), app.reasonCode(),
                app.decisionNote(), app.disqualified(), app.reapplyAfter(), app.submissionCount(), app.submittedAt(),
                app.updatedAt(), apps.timeline(app.id()), apps.accessLog(app.id()),
                viewFor(app, cases.find(app.id()).orElse(null), actor, settingsRepo.load()), approval);
    }

    private static SellingCaseSummary summary(SellingApplication a, ApplicationCaseView workflow) {
        int step = switch (a.status()) {
            case PENDING_REVIEW -> 1;
            case FLAGGED, AWAITING_FINAL_APPROVAL -> 2;
            default -> 0;
        };
        return new SellingCaseSummary(a.id(), a.reference(), a.orgId(), a.applicantName(), a.scope().type().name(),
                a.scope().key(), a.scopeLabel(), a.kind(), a.source(), a.status().name(), step, a.reviewerId(),
                a.failedChecks(), a.submittedAt(), a.updatedAt(), workflow);
    }

    // ---------------------------------------------------------------- helpers

    private Actor viewer() {
        return access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_ASSIGN);
    }

    private ApplicationCase give(SellingApplication app, ApplicationCase c, String assigneeId, String assigneeName,
                                 String byId, String byName, String text) {
        Instant now = Instant.now(clock);
        ApplicationCase next = cases.save(c.assign(assigneeId, assigneeName, byId, byName, now));
        workflowEntry(app, ReviewAction.ASSIGNED, byId, byName, text, now);
        return next;
    }

    private void notifyAssigned(SellingApplication app, ApplicationCase c, String reason, String byName, String note) {
        events.publish(SellerEvents.APPLICATION_ASSIGNED, SellerEvents.AGGREGATE_APPLICATION, app.id(),
                new AssignmentEventPayload(app.id(), app.reference(), app.scopeLabel(), KIND,
                        c.stage() == null ? null : c.stage().name(), reason, c.assigneeId(), c.assigneeName(),
                        nameOr(byName), List.of(c.assigneeId()), 0, note, Instant.now(clock)));
    }

    private void workflowEntry(SellingApplication app, ReviewAction action, String actorId, String actorName,
                               String note, Instant now) {
        apps.appendTimeline(new TimelineEntry(UUID.randomUUID(), app.id(), action, app.status(), app.status(),
                actorId, null, note, List.of(), now, actorName, null));
    }

    private StaffPerson eligiblePerson(SellingApplication app, ReviewStage stage, String assigneeId) {
        Optional<StaffPerson> person = directory.withPermission(stage.permission()).stream()
                .filter(p -> p.id().equals(assigneeId)).findFirst();
        if (person.isEmpty()) {
            String name = directory.people(List.of(assigneeId)).stream().map(StaffPerson::name).findFirst()
                    .orElse(null);
            SellingAssignmentRules.require(app, stage, assigneeId, false, name);
        }
        SellingAssignmentRules.require(app, stage, assigneeId, true, person.orElseThrow().name());
        return person.orElseThrow();
    }

    private List<StaffPerson> eligiblePeople(SellingApplication app, ReviewStage stage) {
        return directory.withPermission(stage.permission()).stream()
                .filter(p -> SellingAssignmentRules.eligible(app, stage, p.id(), true)).toList();
    }

    private static boolean canManage(Actor actor, ReviewStage stage, ApplicationCase c) {
        return actor.can(SellerPermissions.APPLICATIONS_ASSIGN) || (c != null && c.escalatedTo(actor.subject()))
                || (stage == ReviewStage.APPROVAL && actor.can(SellerPermissions.APPLICATIONS_APPROVE));
    }

    private static void requireManager(Actor actor, ReviewStage stage, ApplicationCase c) {
        boolean ownCase = actor.subject().equals(c.assigneeId());
        if (!canManage(actor, stage, c) && !(ownCase && !c.assigned())) {
            throw new SellerForbidden("seller.assign-not-allowed",
                    "Only a supervisor (or an approver at the approval stage) can assign cases");
        }
    }

    private ReviewStage stageOf(SellingApplication app) {
        return ReviewStage.of(app.status()).orElseThrow(() -> new SellerStateConflict("seller.not-in-review",
                app.reference() + " is not waiting for a reviewer or approver"));
    }

    private ApplicationCase current(SellingApplication app) {
        return cases.find(app.id()).orElseGet(() -> ApplicationCase.idle(app.id(), app.updatedAt())
                .enter(ReviewStage.of(app.status()).orElse(null), app.updatedAt()));
    }

    private SellingApplication lock(String id) {
        return apps.lockById(id).orElseThrow(() -> notFound(id));
    }

    private SellingApplication find(String id) {
        return apps.findById(id).orElseThrow(() -> notFound(id));
    }

    private String displayName(String given) {
        if (given != null && !given.isBlank()) {
            return given.trim();
        }
        String full = profiles.current().fullName();
        return full == null || full.isBlank() ? UNKNOWN : full.trim();
    }

    private static String nameOr(String name) {
        return name == null || name.isBlank() ? UNKNOWN : name;
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("seller.selling-application-not-found", "Application " + id + " not found");
    }

    private <T> Optional<T> safe(Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (RuntimeException e) {
            LOG.warn("Staff directory unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
