package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.DecisionKind;
import com.afrotransact.deliveryruns.domain.InvalidTransitionException;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PaymentSettings;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RefundStatus;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.domain.WindowDecision;
import com.afrotransact.deliveryruns.domain.WindowOutcome;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.PreorderDecisionLog;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository.WindowTotals;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PreorderDecisionServiceImpl implements PreorderDecisionService {

    private static final Logger LOG = LoggerFactory.getLogger(PreorderDecisionServiceImpl.class);

    private final PreorderWindowRepository windows;
    private final PreorderOrderRepository orders;
    private final PreorderDecisionLog decisions;
    private final PreorderEventPublisher events;
    private final PreorderSupport support;
    private final RunRepository runs;
    private final LocationRepository locations;
    private final RunLifecycle lifecycle;
    private final PermissionChecker permissions;
    private final Clock clock;

    PreorderDecisionServiceImpl(PreorderWindowRepository windows, PreorderOrderRepository orders,
                                PreorderDecisionLog decisions, PreorderEventPublisher events,
                                PreorderSupport support, RunRepository runs, LocationRepository locations,
                                RunLifecycle lifecycle, PermissionChecker permissions, Clock clock) {
        this.windows = windows;
        this.orders = orders;
        this.decisions = decisions;
        this.events = events;
        this.support = support;
        this.runs = runs;
        this.locations = locations;
        this.lifecycle = lifecycle;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public WindowView decide(UUID windowId, Choice choice, String reason, Long expectedVersion) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        if (choice == null) {
            throw new RuleViolation("delivery-runs.invalid-decision", "Choose CANCEL or PROCEED");
        }
        Instant now = Instant.now(clock);
        PreorderWindow w = support.lock(windowId, expectedVersion);
        if (w.outcome() != WindowOutcome.AWAITING_DECISION) {
            throw new InvalidTransitionException(w.status(now).name(), choice.name());
        }
        if (choice == Choice.CANCEL) {
            return cancelLocked(w, reason, actor, now);
        }
        int count = counted(w);
        WindowDecision decision = WindowDecision.byStaff(w.id(), DecisionKind.PROCEED, reason, actor.subject(), count,
                w.details().minOrders(), now);
        PreorderWindow saved = windows.update(w.proceed(now));
        decisions.append(decision);
        events.decision(DeliveryRunsEvents.PREORDER_WINDOW_DECIDED, saved, payment(), now, decision, null);
        LOG.info("Pre-order {} proceeds below its minimum ({} of {}) by {}: {}", saved.code(), count,
                saved.details().minOrders(), actor.subject(), decision.reason());
        return support.view(saved, now);
    }

    @Override
    @Transactional
    public WindowView cancel(UUID windowId, String reason, Long expectedVersion) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        return cancelLocked(support.lock(windowId, expectedVersion), reason, actor, now);
    }

    private WindowView cancelLocked(PreorderWindow w, String reason, Actor actor, Instant now) {
        List<PreorderOrder> all = orders.findByWindow(w.id());
        int count = (int) all.stream().filter(PreorderOrder::counts).count();
        WindowDecision decision = WindowDecision.byStaff(w.id(), DecisionKind.CANCEL, reason, actor.subject(), count,
                w.details().minOrders(), now);
        PreorderWindow cancelled = w.cancel(now);
        cancelRun(w, decision.reason(), now);
        PreorderWindow saved = windows.update(cancelled);
        decisions.append(decision);
        for (PreorderOrder o : all) {
            if (o.counts()) {
                orders.recordRefund(o.orderId(), RefundStatus.PENDING, null, null, now);
            }
        }
        events.decision(DeliveryRunsEvents.PREORDER_WINDOW_CANCELLED, saved, payment(), now, decision, all);
        events.decision(DeliveryRunsEvents.PREORDER_WINDOW_DECIDED, saved, payment(), now, decision, null);
        LOG.info("Pre-order {} cancelled by {} with {} orders to refund: {}", saved.code(), actor.subject(), count,
                decision.reason());
        return support.view(saved, now);
    }

    /** The run the window's orders were handed to is cancelled with it (refused once it is on the road). */
    private void cancelRun(PreorderWindow w, String reason, Instant now) {
        if (w.runId() == null) {
            return;
        }
        Optional<Run> found = runs.lockById(w.runId());
        if (found.isEmpty() || found.get().status().isTerminal()) {
            return;
        }
        Run run = found.get();
        if (!run.status().canMoveTo(RunStatus.CANCELLED)) {
            throw new InvalidTransitionException(run.status().name(), RunStatus.CANCELLED.name());
        }
        Location location = locations.findById(run.locationId()).orElseThrow();
        lifecycle.cancel(location, run, "Pre-order " + w.code() + " cancelled: " + reason, now);
    }

    @Override
    @Transactional
    public WindowView closeEarly(UUID windowId, String reason, Long expectedVersion) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        Instant now = Instant.now(clock);
        PreorderWindow w = support.lock(windowId, expectedVersion);
        int count = counted(w);
        WindowDecision decision = WindowDecision.byStaff(w.id(), DecisionKind.EARLY_CLOSE, reason, actor.subject(),
                count, w.details().minOrders(), now);
        PreorderWindow saved = windows.update(w.closeEarly(count, now));
        decisions.append(decision);
        PaymentSettings payment = payment();
        events.decision(DeliveryRunsEvents.PREORDER_WINDOW_DECIDED, saved, payment, now, decision, null);
        events.window(DeliveryRunsEvents.PREORDER_WINDOW_UPDATED, saved, payment, now, actor.subject());
        return support.view(saved, now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WindowDecision> log(UUID windowId) {
        permissions.require(DeliveryRunsPermissions.READ);
        support.find(windowId);
        return decisions.findByWindow(windowId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<RefundStatus, Integer> refunds(UUID windowId) {
        permissions.require(DeliveryRunsPermissions.READ);
        support.find(windowId);
        return orders.refundCounts(windowId);
    }

    private int counted(PreorderWindow w) {
        return orders.totals(List.of(w.id())).getOrDefault(w.id(), new WindowTotals(0, 0)).orders();
    }

    private PaymentSettings payment() {
        return support.coverage().settings().payment();
    }
}
