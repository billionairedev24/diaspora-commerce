package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunAction;
import com.afrotransact.deliveryruns.domain.RunActionKind;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.HandoverCodes;
import com.afrotransact.deliveryruns.port.RunActionLog;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import org.springframework.stereotype.Component;

/**
 * Persists run transitions together with their slot changes, events and action log entry (one transaction, the
 * caller's), so staff actions and the cutoff job behave the same.
 */
@Component
class RunLifecycle {

    private final RunRepository runs;
    private final RunOrderRepository runOrders;
    private final HandoverCodes codes;
    private final DeliveryRunsEventPublisher events;
    private final RunActionLog log;
    private final PreorderDeliveryDayService deliveryDay;

    RunLifecycle(RunRepository runs, RunOrderRepository runOrders, HandoverCodes codes,
                 DeliveryRunsEventPublisher events, RunActionLog log, PreorderDeliveryDayService deliveryDay) {
        this.runs = runs;
        this.runOrders = runOrders;
        this.codes = codes;
        this.events = events;
        this.log = log;
        this.deliveryDay = deliveryDay;
    }

    /**
     * CUT_OFF → CONFIRMED: active slots are confirmed (pickup-point runs get handover codes). A confirmation with
     * fewer orders than the minimum is a staff override and is logged as such.
     */
    Run confirm(Location location, Run cutOff, RunStatus previous, Instant now, RunDecision by) {
        boolean override = !cutOff.minimumMet();
        Run confirmed = runs.update(cutOff.confirm(now));
        List<RunOrder> orders = new ArrayList<>();
        for (RunOrder o : runOrders.findActiveByRun(confirmed.id())) {
            String code = confirmed.terms().dropMode() == DropMode.PICKUP_POINT ? codes.next() : null;
            orders.add(runOrders.update(o.confirm(code, now)));
        }
        log.append(RunAction.of(override ? RunActionKind.CONFIRM_OVERRIDE : RunActionKind.CONFIRM, by.reason(),
                by.actor(), previous, confirmed, null, now));
        events.run(DeliveryRunsEvents.RUN_CONFIRMED, location, confirmed, previous, null, null, orders);
        return confirmed;
    }

    /** Cancels the run and every active slot (orders release stock and payment holds on the event). */
    Run cancel(Location location, Run run, RunDecision by, Instant now) {
        RunStatus previous = run.status();
        Run cancelled = runs.update(run.cancel(by.reason(), now));
        List<RunOrder> orders = new ArrayList<>();
        for (RunOrder o : runOrders.findActiveByRun(cancelled.id())) {
            orders.add(runOrders.update(o.cancel(now)));
        }
        log.append(RunAction.of(RunActionKind.CANCEL, by.reason(), by.actor(), previous, cancelled, null, now));
        events.run(DeliveryRunsEvents.RUN_CANCELLED, location, cancelled, previous, null, by.reason(), orders);
        return cancelled;
    }

    Run extended(Location location, Run extended, RunStatus previous, Instant previousCutoff, RunDecision by,
                 Instant now) {
        Run saved = runs.update(extended);
        log.append(RunAction.of(RunActionKind.EXTEND, by.reason(), by.actor(), previous, saved, previousCutoff, now));
        events.run(DeliveryRunsEvents.RUN_EXTENDED, location, saved, previous, previousCutoff, by.reason(), null);
        return saved;
    }

    /**
     * Saves a plain transition and publishes {@code type} (no event when null); orders are attached when asked. A run
     * that leaves with a pre-order window's orders tells the window's customers (the window's own message) and is
     * marked as the window's, so notifications does not send the generic run message on top.
     */
    Run moved(Location location, Run moved, RunStatus previous, String type, boolean withOrders) {
        Run saved = runs.update(moved);
        if (type != null) {
            List<RunOrder> orders = withOrders ? runOrders.findActiveByRun(saved.id()) : null;
            String reason = DeliveryRunsEvents.RUN_OUT_FOR_DELIVERY.equals(type)
                    ? deliveryDay.onRunOutForDelivery(saved, orders).orElse(null) : null;
            events.run(type, location, saved, previous, null, reason, orders);
        }
        return saved;
    }
}
