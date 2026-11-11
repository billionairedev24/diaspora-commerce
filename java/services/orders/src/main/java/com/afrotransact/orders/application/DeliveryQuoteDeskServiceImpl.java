package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.orders.domain.DeliveryQuote;
import com.afrotransact.orders.domain.DeliveryQuote.Answer;
import com.afrotransact.orders.domain.DeliveryQuote.Decline;
import com.afrotransact.orders.domain.DeliveryQuote.Escalation;
import com.afrotransact.orders.domain.DeliveryQuote.Holder;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.QuoteAction;
import com.afrotransact.orders.domain.QuoteSettings;
import com.afrotransact.orders.domain.QuoteSettings.AutoAssign;
import com.afrotransact.orders.domain.QuoteSettings.Target;
import com.afrotransact.orders.domain.QuoteSla;
import com.afrotransact.orders.domain.QuoteSnapshot.Line;
import com.afrotransact.orders.domain.QuoteStatus;
import com.afrotransact.orders.port.DeliveryQuoteRepository;
import com.afrotransact.orders.port.DeliveryQuoteRepository.QuoteEvent;
import com.afrotransact.orders.port.DeliveryQuoteRepository.Search;
import com.afrotransact.orders.port.DeliveryQuoteSettingsRepository;
import com.afrotransact.orders.port.Page;
import com.afrotransact.orders.port.StaffDirectory;
import com.afrotransact.orders.port.StaffDirectory.StaffPerson;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery quote desk. Every action locks the quote row first, so two people (or two replicas) acting at once
 * serialise; every change writes a timeline row (the audit trail) and, where somebody must be told, an outbox event in
 * the same transaction. Logs carry ids and references only.
 */
@Service
class DeliveryQuoteDeskServiceImpl implements DeliveryQuoteDeskService {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryQuoteDeskServiceImpl.class);
    static final String SYSTEM = "system";
    static final int MIN_REASON = 5;
    private static final String UNKNOWN = "Unknown user";
    private static final int OVERDUE_SCAN = 1000;

    private final OrdersAccess access;
    private final DeliveryQuoteRepository quotes;
    private final DeliveryQuoteSettingsRepository settingsRepo;
    private final StaffDirectory directory;
    private final DomainEventPublisher events;
    private final OrdersSettings orders;
    private final Clock clock;

    DeliveryQuoteDeskServiceImpl(OrdersAccess access, DeliveryQuoteRepository quotes,
                                 DeliveryQuoteSettingsRepository settingsRepo, StaffDirectory directory,
                                 DomainEventPublisher events, OrdersSettings orders, Clock clock) {
        this.access = access;
        this.quotes = quotes;
        this.settingsRepo = settingsRepo;
        this.directory = directory;
        this.events = events;
        this.orders = orders;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    @Override
    @Transactional(readOnly = true)
    public QuoteCaseView.Page queue(String queue, String assigneeId, boolean unassigned, boolean overdue,
                                    boolean escalated, String text, int page, int size) {
        Actor actor = viewer();
        int p = Math.max(0, page);
        int n = Math.min(100, Math.max(1, size));
        Search.Queue q = parseQueue(queue);
        QuoteSettings settings = settingsRepo.load();
        Instant now = Instant.now(clock);
        if (overdue) {
            List<QuoteCaseView> all = quotes.search(new Search(q, actor.subject(), blank(assigneeId), unassigned,
                    escalated, text, 0, OVERDUE_SCAN)).items().stream()
                    .filter(c -> c.status().awaitingStaff() && due(c, settings).isBefore(now.plusMillis(1)))
                    .map(c -> caseView(c, actor, settings, now, false)).toList();
            int from = Math.min(all.size(), p * n);
            return new QuoteCaseView.Page(all.subList(from, Math.min(all.size(), from + n)), p, n, all.size());
        }
        Page<DeliveryQuote> found = quotes.search(new Search(q, actor.subject(), blank(assigneeId), unassigned,
                escalated, text, p * n, n));
        return new QuoteCaseView.Page(found.items().stream().map(c -> caseView(c, actor, settings, now, false))
                .toList(), p, n, found.total());
    }

    @Override
    @Transactional(readOnly = true)
    public QuoteCaseView detail(String id) {
        Actor actor = viewer();
        return caseView(find(id), actor, settingsRepo.load(), Instant.now(clock), true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffPerson> candidates(String id) {
        Actor actor = viewer();
        DeliveryQuote q = find(id);
        if (!q.status().awaitingStaff() || !canManage(actor, q)) {
            return List.of();
        }
        return directory.withPermission(OrdersPermissions.QUOTES_AGREE);
    }

    // ---------------------------------------------------------------- who has it

    @Override
    @Transactional
    public QuoteCaseView claim(String id, String actorName) {
        Actor actor = access.quoteStaff(OrdersPermissions.QUOTES_AGREE);
        DeliveryQuote q = lock(id);
        if (actor.subject().equals(q.holder().assigneeId()) && q.status().awaitingStaff()) {
            return view(q, actor, false);
        }
        requireAwaitingStaff(q, "taken");
        if (q.assigned()) {
            throw new OrdersStateConflict("orders.quote-already-assigned", q.reference() + " is already with "
                    + nameOr(q.holder().assigneeName()) + ". A supervisor can reassign it.");
        }
        String name = displayName(actorName);
        return view(give(q, actor.subject(), name, actor.subject(), name, QuoteAction.CLAIMED,
                "Claimed by " + name), actor, true);
    }

    @Override
    @Transactional
    public QuoteCaseView assign(String id, String assigneeId, String note, String actorName) {
        Actor actor = access.quoteStaff(OrdersPermissions.QUOTES_AGREE, OrdersPermissions.QUOTES_ASSIGN);
        if (assigneeId == null || assigneeId.isBlank()) {
            throw new OrdersRuleViolation("orders.assignee-required", "Choose who to assign it to");
        }
        DeliveryQuote q = lock(id);
        requireAwaitingStaff(q, "assigned");
        requireManager(actor, q);
        String assignee = assigneeId.trim();
        if (assignee.equals(q.holder().assigneeId())) {
            return view(q, actor, false);
        }
        StaffPerson person = directory.withPermission(OrdersPermissions.QUOTES_AGREE).stream()
                .filter(p -> p.id().equals(assignee)).findFirst()
                .orElseThrow(() -> new OrdersRuleViolation("orders.assignee-not-eligible",
                        "That person can't hold delivery quotes: they need the permission to agree quotes"));
        String by = displayName(actorName);
        String text = (assignee.equals(actor.subject()) ? "Taken over by " + person.name() : "Assigned to "
                + person.name()) + (q.assigned() ? " (was with " + nameOr(q.holder().assigneeName()) + ")" : "")
                + (note == null || note.isBlank() ? "" : ": " + note.trim());
        DeliveryQuote next = give(q, person.id(), person.name(), actor.subject(), by, QuoteAction.ASSIGNED, text);
        notifyAssigned(next, "ASSIGNED", by, note);
        return view(next, actor, true);
    }

    @Override
    @Transactional
    public QuoteCaseView release(String id, String note, String actorName) {
        Actor actor = access.quoteStaff(OrdersPermissions.QUOTES_AGREE, OrdersPermissions.QUOTES_ASSIGN);
        DeliveryQuote q = lock(id);
        if (!q.assigned() || !q.status().awaitingStaff()) {
            return view(q, actor, false);
        }
        if (!actor.subject().equals(q.holder().assigneeId())) {
            requireManager(actor, q);
        }
        Instant now = Instant.now(clock);
        String by = displayName(actorName);
        DeliveryQuote next = quotes.save(q.release(now));
        record(next, QuoteAction.RELEASED, q.status(), actor.subject(), by, "Released by " + by + " (was with "
                + nameOr(q.holder().assigneeName()) + "); back to the pool" + (note == null || note.isBlank() ? ""
                : ". " + note.trim()), null, now);
        return view(next, actor, true);
    }

    @Override
    @Transactional
    public QuoteCaseView escalate(String id, String reason, String actorName) {
        Actor actor = access.quoteStaff(OrdersPermissions.QUOTES_AGREE, OrdersPermissions.QUOTES_ASSIGN);
        if (reason == null || reason.isBlank()) {
            throw new OrdersRuleViolation("orders.reason-required", "Say why you are escalating this case");
        }
        DeliveryQuote q = lock(id);
        requireAwaitingStaff(q, "escalated");
        boolean holder = actor.subject().equals(q.holder().assigneeId());
        if (!holder && !actor.can(OrdersPermissions.QUOTES_ASSIGN)) {
            throw new ForbiddenActionException("orders.escalate-not-allowed",
                    "Only the person who has this case or a supervisor can escalate it");
        }
        if (q.escalation().level() >= 2) {
            throw new OrdersStateConflict("orders.quote-already-escalated",
                    q.reference() + " is already at the highest escalation level");
        }
        int level = q.escalation().level() + 1;
        List<StaffPerson> targets = resolveTargets(settingsRepo.load().at(level));
        DeliveryQuote next = raise(q, level, reason.trim(), targets, displayName(actorName), actor.subject(),
                Instant.now(clock));
        return view(next, actor, true);
    }

    // ---------------------------------------------------------------- the decision

    @Override
    @Transactional
    public QuoteCaseView agree(String id, long amountMinor, String reason, String actorName) {
        Actor actor = access.quoteStaff(OrdersPermissions.QUOTES_AGREE);
        QuoteSettings s = settingsRepo.load();
        Map<String, String> problems = new LinkedHashMap<>();
        if (reason == null || reason.trim().length() < MIN_REASON) {
            problems.put("reason", "Say why this amount (at least " + MIN_REASON + " characters)");
        }
        if (amountMinor < s.minAmountMinor() || amountMinor > s.maxAmountMinor()) {
            problems.put("amountMinor", "The amount must be between " + money(s.minAmountMinor()) + " and "
                    + money(s.maxAmountMinor()));
        }
        if (!problems.isEmpty()) {
            throw new OrdersRuleViolation("orders.invalid-quote-amount", String.join(". ", problems.values()));
        }
        DeliveryQuote q = lock(id);
        String by = displayName(actorName);
        if (q.status() == QuoteStatus.READY && q.answer() != null && q.answer().amountMinor() == amountMinor
                && actor.subject().equals(q.answer().byId())) {
            return view(q, actor, false); // a retried request
        }
        requireAwaitingStaff(q, "agreed");
        requireMayDecide(actor, q);
        Instant now = Instant.now(clock);
        Answer answer = new Answer(amountMinor, reason.trim(), actor.subject(), by, now, now.plus(s.validity()));
        DeliveryQuote next = quotes.save(q.agree(answer, now));
        record(next, QuoteAction.AGREED, q.status(), actor.subject(), by, "Agreed " + money(amountMinor) + ": "
                + reason.trim(), amountMinor, now);
        events.publish(QuoteEvents.READY, OrdersEvents.AGGREGATE, next.id(), new QuoteEvents.Ready(next.id(),
                next.reference(), next.customerId(), amountMinor, next.snapshot().currency(), answer.expiresAt(), now));
        return view(next, actor, true);
    }

    @Override
    @Transactional
    public QuoteCaseView decline(String id, String reason, String actorName) {
        Actor actor = access.quoteStaff(OrdersPermissions.QUOTES_AGREE);
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new OrdersRuleViolation("orders.reason-required", "Say why you are declining (at least "
                    + MIN_REASON + " characters)");
        }
        DeliveryQuote q = lock(id);
        if (q.status() == QuoteStatus.DECLINED && actor.subject().equals(q.decline().byId())) {
            return view(q, actor, false);
        }
        requireAwaitingStaff(q, "declined");
        requireMayDecide(actor, q);
        Instant now = Instant.now(clock);
        String by = displayName(actorName);
        DeliveryQuote next = quotes.save(q.decline(new Decline(reason.trim(), actor.subject(), by, now), now));
        record(next, QuoteAction.DECLINED, q.status(), actor.subject(), by, "Declined: " + reason.trim(), null, now);
        events.publish(QuoteEvents.DECLINED, OrdersEvents.AGGREGATE, next.id(), lifecycle(next, now));
        return view(next, actor, true);
    }

    // ---------------------------------------------------------------- hand-out, escalation and expiry (jobs)

    @Override
    @Transactional
    public void handOut(String id) {
        DeliveryQuote q = quotes.lockById(id).orElse(null);
        QuoteSettings s = settingsRepo.load();
        if (q == null || q.assigned() || s.autoAssign() == AutoAssign.NONE) {
            return;
        }
        Optional<List<StaffPerson>> pool = safe(() -> directory.withPermission(OrdersPermissions.QUOTES_AGREE));
        if (pool.isEmpty() || pool.get().isEmpty()) {
            return;
        }
        List<StaffPerson> people = pool.get().stream().sorted(Comparator.comparing(StaffPerson::id)).toList();
        StaffPerson pick;
        if (s.autoAssign() == AutoAssign.LEAST_LOADED) {
            Map<String, Integer> load = quotes.openLoad();
            pick = people.stream().min(Comparator.<StaffPerson>comparingInt(p -> load.getOrDefault(p.id(), 0))
                    .thenComparing(StaffPerson::id)).orElseThrow();
        } else {
            String last = settingsRepo.lock().roundRobinCursor();
            pick = people.stream().filter(p -> last == null || p.id().compareTo(last) > 0).findFirst()
                    .orElse(people.getFirst());
            settingsRepo.saveCursor(pick.id());
        }
        DeliveryQuote next = give(q, pick.id(), pick.name(), SYSTEM, "Auto-assignment", QuoteAction.ASSIGNED,
                "Assigned to " + pick.name() + " (auto-assignment: " + s.autoAssign().name().toLowerCase(Locale.ROOT)
                .replace('_', ' ') + ")");
        notifyAssigned(next, "AUTO_ASSIGNED", "Auto-assignment", null);
    }

    @Override
    @Transactional
    public boolean escalateIfDue(String id, Instant now) {
        DeliveryQuote q = quotes.lockById(id).orElse(null);
        if (q == null || !q.status().awaitingStaff()) {
            return false;
        }
        QuoteSettings s = settingsRepo.load();
        Duration age = Duration.between(q.stageEnteredAt(), now);
        Instant due = due(q, s);
        boolean overdue = !now.isBefore(due);
        boolean untaken = !q.assigned() && age.compareTo(Duration.ofHours(s.unassignedAfterHours())) >= 0;
        int want = !now.isBefore(due.plus(Duration.ofHours(s.level2AfterHours()))) ? 2 : overdue || untaken ? 1 : 0;
        if (want <= q.escalation().level()) {
            return false;
        }
        int level = q.escalation().level() + 1;
        List<StaffPerson> targets = resolveTargets(s.at(level));
        if (targets.isEmpty()) {
            return false; // nobody is set up for this level (or the directory is down): tried again next run
        }
        String reason = overdue ? "Overdue: waiting " + age.toHours() + " h (service level " + s.slaBusinessDays()
                + " business day" + (s.slaBusinessDays() == 1 ? "" : "s") + ")"
                : "Nobody has taken it for " + age.toHours() + " h";
        raise(q, level, reason, targets, "Escalation", SYSTEM, now);
        return true;
    }

    @Override
    @Transactional
    public boolean expireIfDue(String id, Instant now) {
        DeliveryQuote q = quotes.lockById(id).orElse(null);
        if (q == null || q.status() != QuoteStatus.READY || q.answer() == null
                || q.answer().expiresAt().isAfter(now)) {
            return false;
        }
        DeliveryQuote next = quotes.save(q.expire(now));
        record(next, QuoteAction.EXPIRED, q.status(), SYSTEM, "System", "Not used before "
                + q.answer().expiresAt(), q.answer().amountMinor(), now);
        events.publish(QuoteEvents.EXPIRED, OrdersEvents.AGGREGATE, next.id(), lifecycle(next, now));
        return true;
    }

    // ---------------------------------------------------------------- internals

    private DeliveryQuote raise(DeliveryQuote q, int level, String reason, List<StaffPerson> targets, String byName,
                                String byId, Instant now) {
        DeliveryQuote next = quotes.save(q.escalate(new Escalation(level, now, reason,
                targets.stream().map(StaffPerson::id).toList(), targets.stream().map(StaffPerson::name).toList()),
                now));
        String names = targets.isEmpty() ? "no one is set up for this level yet"
                : String.join(", ", targets.stream().map(StaffPerson::name).toList());
        record(next, QuoteAction.ESCALATED, q.status(), byId, byName, "Escalated to level " + level + " (" + names
                + "): " + reason, null, now);
        if (!targets.isEmpty()) {
            events.publish(QuoteEvents.ESCALATED, OrdersEvents.AGGREGATE, q.id(), new QuoteEvents.Assignment(q.id(),
                    q.reference(), q.snapshot().place().summary(), reason, q.holder().assigneeName(), byName,
                    targets.stream().map(StaffPerson::id).toList(), level, reason, now));
        }
        return next;
    }

    private DeliveryQuote give(DeliveryQuote q, String assigneeId, String assigneeName, String byId, String byName,
                               QuoteAction action, String text) {
        Instant now = Instant.now(clock);
        DeliveryQuote next = quotes.save(q.assign(new Holder(assigneeId, assigneeName, byId, byName, now), now));
        record(next, action, q.status(), byId, byName, text, null, now);
        return next;
    }

    private void notifyAssigned(DeliveryQuote q, String reason, String byName, String note) {
        events.publish(QuoteEvents.ASSIGNED, OrdersEvents.AGGREGATE, q.id(), new QuoteEvents.Assignment(q.id(),
                q.reference(), q.snapshot().place().summary(), reason, q.holder().assigneeName(), nameOr(byName),
                List.of(q.holder().assigneeId()), 0, note, Instant.now(clock)));
    }

    private QuoteEvents.Lifecycle lifecycle(DeliveryQuote q, Instant now) {
        return new QuoteEvents.Lifecycle(q.id(), q.reference(), q.customerId(), q.snapshot().place().summary(),
                q.snapshot().lines().stream().mapToInt(Line::quantity).sum(), now);
    }

    private void record(DeliveryQuote q, QuoteAction action, QuoteStatus from, String actorId, String actorName,
                        String note, Long amount, Instant now) {
        quotes.appendEvent(new QuoteEvent(UUID.randomUUID().toString(), q.id(), action, from, q.status(), actorId,
                actorName, note, amount, now));
        LOG.info("Delivery quote {} {} by {} ({} -> {})", q.reference(), action, actorId, from, q.status());
    }

    /** The people a level goes to: holders of its role plus the named people. */
    private List<StaffPerson> resolveTargets(Target target) {
        Map<String, StaffPerson> out = new LinkedHashMap<>();
        if (target.roleKey() != null) {
            safe(() -> directory.withRole(target.roleKey())).orElse(List.of()).forEach(p -> out.put(p.id(), p));
        }
        if (!target.userIds().isEmpty()) {
            safe(() -> directory.people(target.userIds())).orElse(List.of()).forEach(p -> out.put(p.id(), p));
        }
        return List.copyOf(out.values());
    }

    private Instant due(DeliveryQuote q, QuoteSettings s) {
        return QuoteSla.dueAt(q.stageEnteredAt(), s.slaBusinessDays(), ZoneId.of(orders.zone()));
    }

    private QuoteCaseView view(DeliveryQuote q, Actor actor, boolean withTimeline) {
        return caseView(q, actor, settingsRepo.load(), Instant.now(clock), true);
    }

    private QuoteCaseView caseView(DeliveryQuote q, Actor actor, QuoteSettings s, Instant now, boolean timeline) {
        boolean waiting = q.status().awaitingStaff();
        boolean mine = waiting && actor.subject().equals(q.holder().assigneeId());
        boolean agree = actor.can(OrdersPermissions.QUOTES_AGREE);
        boolean manage = waiting && canManage(actor, q);
        Instant due = waiting ? due(q, s) : null;
        String sla = waiting ? QuoteSla.state(q.stageEnteredAt(), due, now, s.dueSoonPercent()).name() : null;
        long waited = waiting ? Math.max(0, Duration.between(q.stageEnteredAt(), now).getSeconds()) : 0;
        String holder = !waiting ? null : q.assigned() ? "with " + q.holder().assigneeName() : "any customer care agent";
        boolean decide = waiting && agree && (mine || !q.assigned() || canManage(actor, q));
        QuoteCaseView.Workflow w = new QuoteCaseView.Workflow(waiting ? "QUOTE" : null, holder,
                q.holder().assigneeId(), q.holder().assigneeName(), q.holder().assignedAt(),
                q.holder().assignedByName(), waiting ? q.stageEnteredAt() : null, waited, sla, due,
                waiting ? q.escalation().level() : 0, q.escalation().at(), q.escalation().reason(),
                q.escalation().targetNames(), mine, waiting && agree && !q.assigned(), manage,
                waiting && q.assigned() && (mine || manage),
                waiting && q.escalation().level() < 2 && (mine || actor.can(OrdersPermissions.QUOTES_ASSIGN)),
                decide);
        List<QuoteCaseView.Entry> entries = timeline ? quotes.events(q.id()).stream()
                .map(e -> new QuoteCaseView.Entry(e.action().name(), e.actorName() == null ? UNKNOWN : e.actorName(),
                        e.note(), e.amountMinor(), e.from() == null ? null : e.from().name(), e.to().name(), e.at()))
                .toList() : List.of();
        Answer a = q.answer();
        Decline d = q.decline();
        QuoteStatus shown = q.status();
        return new QuoteCaseView(q.id(), q.reference(), shown.name(), shown.label(), q.snapshot().customerName(),
                q.snapshot().place().summary(), q.snapshot().currency(), q.snapshot().packages(),
                q.snapshot().lines(), q.snapshot().lines().stream().mapToInt(Line::quantity).sum(),
                q.snapshot().contact().name(), q.snapshot().note(), a == null ? null : a.amountMinor(),
                a == null ? null : a.reason(), a == null ? null : a.byName(), a == null ? null : a.at(),
                a == null ? null : a.expiresAt(), d == null ? null : d.reason(), d == null ? null : d.byName(),
                d == null ? null : d.at(), q.usedOrderId() != null, q.usedAt(), q.createdAt(), w, entries);
    }

    private Actor viewer() {
        return access.quoteStaff(OrdersPermissions.QUOTES_VIEW, OrdersPermissions.QUOTES_AGREE,
                OrdersPermissions.QUOTES_ASSIGN);
    }

    private static boolean canManage(Actor actor, DeliveryQuote q) {
        return actor.can(OrdersPermissions.QUOTES_ASSIGN) || q.escalatedTo(actor.subject());
    }

    private static void requireManager(Actor actor, DeliveryQuote q) {
        boolean takingUnassigned = !q.assigned() && actor.can(OrdersPermissions.QUOTES_AGREE);
        if (!canManage(actor, q) && !takingUnassigned) {
            throw new ForbiddenActionException("orders.assign-not-allowed",
                    "Only a supervisor can assign or take over delivery quotes");
        }
    }

    /** Whoever holds the case decides it; a case in the pool, or held by someone else, needs a manager or a claim. */
    private static void requireMayDecide(Actor actor, DeliveryQuote q) {
        if (q.assigned() && !actor.subject().equals(q.holder().assigneeId()) && !canManage(actor, q)) {
            throw new ForbiddenActionException("orders.quote-held-by-other", q.reference() + " is with "
                    + nameOr(q.holder().assigneeName()) + ". A supervisor can take it over.");
        }
    }

    private static void requireAwaitingStaff(DeliveryQuote q, String what) {
        if (!q.status().awaitingStaff()) {
            throw new OrdersStateConflict("orders.quote-closed", q.reference() + " can't be " + what + ": it is "
                    + q.status().label().toLowerCase(Locale.ROOT));
        }
    }

    private DeliveryQuote find(String id) {
        return parse(id).flatMap(quotes::findById).orElseThrow(() -> notFound());
    }

    private DeliveryQuote lock(String id) {
        return parse(id).flatMap(quotes::lockById).orElseThrow(() -> notFound());
    }

    private static Optional<String> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id).toString());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("orders.quote-not-found", "Delivery quote not found");
    }

    private static Search.Queue parseQueue(String s) {
        if (s == null || s.isBlank()) {
            return Search.Queue.ALL;
        }
        try {
            return Search.Queue.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OrdersRuleViolation("orders.invalid-queue", "Unknown queue " + s);
        }
    }

    private String displayName(String given) {
        return given == null || given.isBlank() ? UNKNOWN : given.trim();
    }

    private static String nameOr(String name) {
        return name == null || name.isBlank() ? UNKNOWN : name;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String money(long minor) {
        return String.format(Locale.ROOT, "$%d.%02d", minor / 100, minor % 100);
    }

    /** The directory is a remote call: a failure must not undo the step that triggered it. */
    private <T> Optional<T> safe(Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (RuntimeException e) {
            LOG.warn("Staff directory unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
