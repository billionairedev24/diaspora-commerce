package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationCase;
import com.afrotransact.seller.domain.AssignmentRules;
import com.afrotransact.seller.domain.AutoAssignMode;
import com.afrotransact.seller.domain.EscalationTarget;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.ReviewSettings;
import com.afrotransact.seller.domain.ReviewStage;
import com.afrotransact.seller.domain.SellerForbidden;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.SlaState;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.port.ApplicationCaseRepository;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.CallerProfileProvider;
import com.afrotransact.seller.port.ReviewSettingsRepository;
import com.afrotransact.seller.port.StaffDirectory;
import com.afrotransact.seller.port.StaffDirectory.StaffPerson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReviewAssignmentServiceImpl implements ReviewAssignmentService {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewAssignmentServiceImpl.class);
    static final String SYSTEM = "system";
    private static final String UNKNOWN = "Unknown user";

    private final ApplicationRepository apps;
    private final ApplicationCaseRepository cases;
    private final ReviewSettingsRepository settingsRepo;
    private final StaffDirectory directory;
    private final AccessGuard access;
    private final CallerProfileProvider profiles;
    private final DomainEventPublisher events;
    private final Clock clock;

    ReviewAssignmentServiceImpl(ApplicationRepository apps, ApplicationCaseRepository cases,
                                ReviewSettingsRepository settingsRepo, StaffDirectory directory, AccessGuard access,
                                CallerProfileProvider profiles, DomainEventPublisher events, Clock clock) {
        this.apps = apps;
        this.cases = cases;
        this.settingsRepo = settingsRepo;
        this.directory = directory;
        this.access = access;
        this.profiles = profiles;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewSettings settings() {
        return settingsRepo.load();
    }

    // ---------------------------------------------------------------- actions

    @Override
    @Transactional
    public ApplicationCaseView claim(String applicationId, String actorName) {
        Actor actor = access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE);
        Application app = lock(applicationId);
        ReviewStage stage = stageOf(app);
        ApplicationCase c = current(app);
        if (actor.subject().equals(c.assigneeId())) {
            return view(app, c, actor);
        }
        if (c.assigned()) {
            throw new SellerStateConflict("seller.already-assigned", app.reference() + " is already with "
                    + nameOr(c.assigneeName()) + ". A supervisor can reassign it.");
        }
        if (!AssignmentRules.eligible(app, stage, actor.subject(), actor.can(stage.permission()))) {
            AssignmentRules.require(app, stage, actor.subject(), actor.can(stage.permission()), "You");
        }
        String name = displayName(actor, actorName);
        return view(app, give(app, c, stage, actor.subject(), name, actor.subject(), name, "Claimed by " + name), actor);
    }

    @Override
    @Transactional
    public ApplicationCaseView assign(String applicationId, String assigneeId, String note, String actorName) {
        Actor actor = access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_ASSIGN);
        if (assigneeId == null || assigneeId.isBlank()) {
            throw new SellerRuleViolation("seller.assignee-required", "Choose who to assign it to",
                    Map.of("assigneeId", "Required"));
        }
        Application app = lock(applicationId);
        ReviewStage stage = stageOf(app);
        ApplicationCase c = current(app);
        requireManager(actor, stage, c);
        String assignee = assigneeId.trim();
        if (assignee.equals(c.assigneeId())) {
            return view(app, c, actor);
        }
        StaffPerson person = eligiblePerson(app, stage, assignee);
        String by = displayName(actor, actorName);
        String text = (assignee.equals(actor.subject()) ? "Taken over by " + person.name() : "Assigned to "
                + person.name()) + (c.assigned() ? " (was with " + nameOr(c.assigneeName()) + ")" : "")
                + (note == null || note.isBlank() ? "" : ": " + note.trim());
        ApplicationCase next = give(app, c, stage, person.id(), person.name(), actor.subject(), by, text);
        notifyAssigned(app, next, "ASSIGNED", by, note);
        return view(app, next, actor);
    }

    @Override
    @Transactional
    public ApplicationCaseView release(String applicationId, String note, String actorName) {
        Actor actor = access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_ASSIGN);
        Application app = lock(applicationId);
        ReviewStage stage = stageOf(app);
        ApplicationCase c = current(app);
        if (!c.assigned()) {
            return view(app, c, actor);
        }
        if (!actor.subject().equals(c.assigneeId())) {
            requireManager(actor, stage, c);
        }
        Instant now = Instant.now(clock);
        String by = displayName(actor, actorName);
        ApplicationCase next = cases.save(c.release(now));
        timeline(app, ReviewAction.RELEASED, actor.subject(), by, "Released by " + by + " (was with "
                + nameOr(c.assigneeName()) + "); back to the pool: awaiting " + stage.noun() + " from any "
                + stage.person() + (note == null || note.isBlank() ? "" : ". " + note.trim()), now);
        return view(app, next, actor);
    }

    @Override
    @Transactional
    public ApplicationCaseView escalate(String applicationId, String reason, String actorName) {
        Actor actor = access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_ASSIGN);
        if (reason == null || reason.isBlank()) {
            throw new SellerRuleViolation("seller.reason-required", "Say why you are escalating this case",
                    Map.of("reason", "Required"));
        }
        Application app = lock(applicationId);
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
        List<StaffPerson> targets = resolveTargets(app, settingsRepo.load().chain(stage).at(level));
        ApplicationCase next = raise(app, c, stage, level, reason.trim(), targets, displayName(actor, actorName),
                actor.subject(), Instant.now(clock));
        return view(app, next, actor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffPerson> candidates(String applicationId) {
        Actor actor = access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_ASSIGN);
        Application app = apps.findById(applicationId).orElseThrow(() -> notFound(applicationId));
        Optional<ReviewStage> stage = ReviewStage.of(app.status());
        if (stage.isEmpty()) {
            return List.of();
        }
        ApplicationCase c = cases.find(app.id()).orElse(null);
        if (!canManage(actor, stage.get(), c)) {
            return List.of();
        }
        return eligiblePeople(app, stage.get());
    }

    // ---------------------------------------------------------------- views

    @Override
    public ApplicationCaseView viewFor(Application app, ApplicationCase workflow, Actor actor,
                                       ReviewSettings settings) {
        Instant now = Instant.now(clock);
        Optional<ReviewStage> stage = ReviewStage.of(app.status());
        ApplicationCase c = workflow == null ? ApplicationCase.idle(app.id(), app.updatedAt()) : workflow;
        ApplicationCase held = workflow == null && stage.isPresent() ? c.enter(stage.get(), app.updatedAt()) : c;
        ApplicationCase shown = stage.isPresent() && held.stage() != stage.get() ? held.enter(stage.get(),
                held.stageEnteredAt()) : held;
        boolean any = stage.isPresent();
        String holder = !any ? null : shown.assigned() ? "with " + shown.assigneeName()
                : "any " + stage.get().person();
        long waiting = any ? Math.max(0, Duration.between(shown.stageEnteredAt(), now).getSeconds()) : 0;
        Duration allowed = any ? settings.sla(stage.get()) : null;
        String sla = any ? SlaState.of(shown.stageEnteredAt(), now, allowed, settings.dueSoonPercent()).name() : null;
        Instant due = any ? shown.stageEnteredAt().plus(allowed) : null;
        boolean mine = any && actor.subject().equals(shown.assigneeId());
        boolean eligible = any && AssignmentRules.eligible(app, stage.get(), actor.subject(),
                actor.can(stage.get().permission()));
        boolean manage = any && canManage(actor, stage.get(), shown);
        return new ApplicationCaseView(any ? stage.get().name() : null, holder, shown.assigneeId(),
                shown.assigneeName(), shown.assignedAt(), shown.assignedByName(), any ? shown.stageEnteredAt() : null,
                waiting, sla, due, any ? shown.escalationLevel() : 0, shown.escalatedAt(), shown.escalationReason(),
                shown.escalationTargetNames(), shown.returnedByName(), shown.returnedAt(), shown.returnNote(),
                shown.returnCount(), mine, any && !shown.assigned() && eligible, manage, any && shown.assigned()
                && (mine || manage), any && shown.escalationLevel() < 2 && (mine || actor.can(
                SellerPermissions.APPLICATIONS_ASSIGN)), any && shown.assigned() && !mine && manage && eligible);
    }

    private ApplicationCaseView view(Application app, ApplicationCase c, Actor actor) {
        return viewFor(app, c, actor, settingsRepo.load());
    }

    // ---------------------------------------------------------------- transitions

    @Override
    @Transactional
    public void transitioned(Application before, Application after, ReviewAction cause, String actorId,
                             String actorName, String note) {
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
        boolean assigned = false;
        if (cause == ReviewAction.SEND_BACK && s.sendBackToReviewer() && before.reviewerId() != null) {
            Optional<StaffPerson> original = safe(() -> eligiblePeople(after, stage)).orElse(List.of()).stream()
                    .filter(p -> p.id().equals(before.reviewerId())).findFirst();
            if (original.isPresent()) {
                c = give(after, c, stage, original.get().id(), original.get().name(), SYSTEM,
                        "Sent back to the original reviewer", "Assigned to " + original.get().name()
                        + " (the original reviewer: the case was sent back)");
                notifyAssigned(after, c, "SENT_BACK", nameOr(actorName), note);
                assigned = true;
            }
        }
        if (!assigned) {
            assigned = autoAssign(after, c, stage, s, cause);
        }
        if (!assigned) {
            notifyPool(after, stage, cause, actorName, note, before.reviewerId());
        }
    }

    /** Applies ROUND_ROBIN / LEAST_LOADED; true when somebody got the case. */
    private boolean autoAssign(Application app, ApplicationCase c, ReviewStage stage, ReviewSettings s,
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
        ApplicationCase next = give(app, c, stage, pick.id(), pick.name(), SYSTEM, "Auto-assignment",
                "Assigned to " + pick.name() + " (auto-assignment: " + s.autoAssign().name().toLowerCase()
                .replace('_', ' ') + ")");
        String reason = stage == ReviewStage.APPROVAL ? "READY_FOR_APPROVAL"
                : cause == ReviewAction.SEND_BACK ? "SENT_BACK" : "AUTO_ASSIGNED";
        notifyAssigned(app, next, reason, "Auto-assignment", null);
        return true;
    }

    private void notifyPool(Application app, ReviewStage stage, ReviewAction cause, String actorName, String note,
                            String previousReviewerId) {
        String reason;
        if (stage == ReviewStage.APPROVAL) {
            reason = "READY_FOR_APPROVAL";
        } else if (cause == ReviewAction.SEND_BACK) {
            reason = "SENT_BACK";
        } else {
            return; // a new submission waits in the pool: the queues show it, nobody is paged
        }
        Optional<List<StaffPerson>> pool = safe(() -> eligiblePeople(app, stage));
        List<String> recipients = new ArrayList<>(pool.orElse(List.of()).stream().map(StaffPerson::id).toList());
        if (reason.equals("SENT_BACK") && previousReviewerId != null && !recipients.contains(previousReviewerId)
                && !previousReviewerId.equals(app.applicantUserId())) {
            recipients.add(previousReviewerId);
        }
        if (recipients.isEmpty()) {
            return;
        }
        events.publish(SellerEvents.APPLICATION_ASSIGNED, SellerEvents.AGGREGATE_APPLICATION, app.id(),
                new AssignmentEventPayload(app.id(), app.reference(), display(app), app.kind().name(), stage.name(),
                        reason, null, null, nameOr(actorName), recipients, 0, note, Instant.now(clock)));
    }

    // ---------------------------------------------------------------- escalation (the job and the manual action)

    @Override
    @Transactional
    public boolean escalateIfDue(String applicationId, Instant now) {
        Application app = apps.lockById(applicationId).orElse(null);
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
            return false; // nobody is set up for this level (or the directory is down): try again next run
        }
        String reason = overdue ? "Overdue: waiting " + age.toHours() + " h at " + stage.noun() + " (service level "
                + sla.toHours() + " h)" : "Nobody has taken it for " + age.toHours() + " h";
        raise(app, c, stage, level, reason, targets, "Escalation", SYSTEM, now);
        return true;
    }

    /**
     * Raises the case to {@code level}, tells that level's targets and writes the timeline. Callers hold the
     * application lock. Idempotent: a level that is already reached changes nothing.
     */
    private ApplicationCase raise(Application app, ApplicationCase c, ReviewStage stage, int level, String reason,
                                  List<StaffPerson> targets, String byName, String byId, Instant now) {
        if (level <= c.escalationLevel()) {
            return c;
        }
        ApplicationCase next = cases.save(c.escalate(level, reason, targets.stream().map(StaffPerson::id).toList(),
                targets.stream().map(StaffPerson::name).toList(), now));
        String names = targets.isEmpty() ? "no one is set up for this level yet"
                : String.join(", ", targets.stream().map(StaffPerson::name).toList());
        timeline(app, ReviewAction.ESCALATED, byId, byName, "Escalated to level " + level + " (" + names + "): "
                + reason, now);
        if (!targets.isEmpty()) {
            events.publish(SellerEvents.APPLICATION_ESCALATED, SellerEvents.AGGREGATE_APPLICATION, app.id(),
                    new AssignmentEventPayload(app.id(), app.reference(), display(app), app.kind().name(),
                            stage.name(), "ESCALATED", c.assigneeId(), c.assigneeName(), byName,
                            targets.stream().map(StaffPerson::id).toList(), level, reason, now));
        }
        return next;
    }

    /** The people a level goes to: holders of its role plus the named people, minus the applicant. */
    private List<StaffPerson> resolveTargets(Application app, EscalationTarget target) {
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

    // ---------------------------------------------------------------- helpers

    private ApplicationCase give(Application app, ApplicationCase c, ReviewStage stage, String assigneeId,
                                 String assigneeName, String byId, String byName, String text) {
        Instant now = Instant.now(clock);
        ApplicationCase next = cases.save(c.assign(assigneeId, assigneeName, byId, byName, now));
        timeline(app, ReviewAction.ASSIGNED, byId, byName, text, now);
        return next;
    }

    private void notifyAssigned(Application app, ApplicationCase c, String reason, String byName, String note) {
        events.publish(SellerEvents.APPLICATION_ASSIGNED, SellerEvents.AGGREGATE_APPLICATION, app.id(),
                new AssignmentEventPayload(app.id(), app.reference(), display(app), app.kind().name(),
                        c.stage() == null ? null : c.stage().name(), reason, c.assigneeId(), c.assigneeName(),
                        nameOr(byName), List.of(c.assigneeId()), 0, note, Instant.now(clock)));
    }

    private void timeline(Application app, ReviewAction action, String actorId, String actorName, String note,
                          Instant now) {
        apps.appendTimeline(new TimelineEntry(UUID.randomUUID(), app.id(), action, app.status(), app.status(),
                actorId, null, note, List.of(), now, actorName, null));
    }

    private StaffPerson eligiblePerson(Application app, ReviewStage stage, String assigneeId) {
        List<StaffPerson> holders = directory.withPermission(stage.permission());
        Optional<StaffPerson> person = holders.stream().filter(p -> p.id().equals(assigneeId)).findFirst();
        if (person.isEmpty()) {
            String name = directory.people(List.of(assigneeId)).stream().map(StaffPerson::name).findFirst()
                    .orElse(null);
            AssignmentRules.require(app, stage, assigneeId, false, name);
        }
        AssignmentRules.require(app, stage, assigneeId, true, person.get().name());
        return person.get();
    }

    private List<StaffPerson> eligiblePeople(Application app, ReviewStage stage) {
        return directory.withPermission(stage.permission()).stream()
                .filter(p -> AssignmentRules.eligible(app, stage, p.id(), true)).toList();
    }

    /** Who may assign, reassign, release for others and take over. */
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

    private ReviewStage stageOf(Application app) {
        return ReviewStage.of(app.status()).orElseThrow(() -> new SellerStateConflict("seller.not-in-review",
                app.reference() + " is not waiting for a reviewer or approver"));
    }

    private ApplicationCase current(Application app) {
        return cases.find(app.id()).orElseGet(() -> ApplicationCase.idle(app.id(), app.updatedAt())
                .enter(ReviewStage.of(app.status()).orElse(null), app.updatedAt()));
    }

    private Application lock(String id) {
        return apps.lockById(id).orElseThrow(() -> notFound(id));
    }

    private String displayName(Actor actor, String given) {
        if (given != null && !given.isBlank()) {
            return given.trim();
        }
        String full = profiles.current().fullName();
        return full == null || full.isBlank() ? UNKNOWN : full.trim();
    }

    private static String nameOr(String name) {
        return name == null || name.isBlank() ? UNKNOWN : name;
    }

    private static String display(Application a) {
        return a.data().business() == null ? null : a.data().business().storeName();
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("seller.application-not-found", "Application " + id + " not found");
    }

    /** The directory is a remote call: a failure must not undo the review step that triggered it. */
    private <T> Optional<T> safe(Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (RuntimeException e) {
            LOG.warn("Staff directory unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
