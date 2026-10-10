package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.HandoverCodes;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import org.springframework.stereotype.Component;

/**
 * Persists run transitions together with their slot changes and events (one transaction, the caller's), so staff
 * actions and the cutoff job behave the same.
 */
@Component
class RunLifecycle {

    private final RunRepository runs;
    private final RunOrderRepository runOrders;
    private final HandoverCodes codes;
    private final DeliveryRunsEventPublisher events;

    RunLifecycle(RunRepository runs, RunOrderRepository runOrders, HandoverCodes codes,
                 DeliveryRunsEventPublisher events) {
        this.runs = runs;
        this.runOrders = runOrders;
        this.codes = codes;
        this.events = events;
    }

    /** CUT_OFF → CONFIRMED: active slots are confirmed (pickup-point runs get handover codes). */
    Run confirm(Location location, Run cutOff, RunStatus previous, Instant now) {
        Run confirmed = runs.update(cutOff.confirm(now));
        List<RunOrder> orders = new ArrayList<>();
        for (RunOrder o : runOrders.findActiveByRun(confirmed.id())) {
            String code = confirmed.terms().dropMode() == DropMode.PICKUP_POINT ? codes.next() : null;
            orders.add(runOrders.update(o.confirm(code, now)));
        }
        events.run(DeliveryRunsEvents.RUN_CONFIRMED, location, confirmed, previous, null, null, orders);
        return confirmed;
    }

    /** Cancels the run and every active slot (orders release stock and payment holds on the event). */
    Run cancel(Location location, Run run, String reason, Instant now) {
        RunStatus previous = run.status();
        Run cancelled = runs.update(run.cancel(reason, now));
        List<RunOrder> orders = new ArrayList<>();
        for (RunOrder o : runOrders.findActiveByRun(cancelled.id())) {
            orders.add(runOrders.update(o.cancel(now)));
        }
        events.run(DeliveryRunsEvents.RUN_CANCELLED, location, cancelled, previous, null, reason, orders);
        return cancelled;
    }

    Run extended(Location location, Run extended, RunStatus previous, Instant previousCutoff, String reason) {
        Run saved = runs.update(extended);
        events.run(DeliveryRunsEvents.RUN_EXTENDED, location, saved, previous, previousCutoff, reason, null);
        return saved;
    }

    /** Saves a plain transition and publishes {@code type} (no event when null); orders are attached when asked. */
    Run moved(Location location, Run moved, RunStatus previous, String type, boolean withOrders) {
        Run saved = runs.update(moved);
        if (type != null) {
            events.run(type, location, saved, previous, null, null,
                    withOrders ? runOrders.findActiveByRun(saved.id()) : null);
        }
        return saved;
    }
}
