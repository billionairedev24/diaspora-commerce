package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import com.afrotransact.deliveryruns.port.PreorderStepLog;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class PreorderDeliveryDayServiceImpl implements PreorderDeliveryDayService {

    private static final Logger LOG = LoggerFactory.getLogger(PreorderDeliveryDayServiceImpl.class);

    private final PreorderWindowRepository windows;
    private final PreorderOrderRepository orders;
    private final PreorderSettingsRepository settings;
    private final PreorderStepLog steps;
    private final PreorderEventPublisher events;
    private final Clock clock;

    PreorderDeliveryDayServiceImpl(PreorderWindowRepository windows, PreorderOrderRepository orders,
                                   PreorderSettingsRepository settings, PreorderStepLog steps,
                                   PreorderEventPublisher events, Clock clock) {
        this.windows = windows;
        this.orders = orders;
        this.settings = settings;
        this.steps = steps;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Optional<String> onRunOutForDelivery(Run run, List<RunOrder> onRun) {
        Optional<PreorderWindow> found = windows.findByRunId(run.id());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PreorderWindow w = found.get();
        String marker = REASON_PREFIX + w.code();
        String step = PreorderSchedulerServiceImpl.comms(CommKind.OUT_FOR_DELIVERY);
        if (!w.active() || !w.details().comms().contains(CommKind.OUT_FOR_DELIVERY)
                || steps.done(w.id()).contains(step)) {
            return Optional.of(marker);
        }
        Instant now = Instant.now(clock);
        // The step log row is the once-only guard: a repeated driver event, a second replica or a staff retry finds it.
        if (!steps.markDone(w.id(), step, now, now)) {
            return Optional.of(marker);
        }
        Map<String, String> codes = new HashMap<>();
        for (RunOrder o : onRun == null ? List.<RunOrder>of() : onRun) {
            if (o.handoverCode() != null) {
                codes.put(o.orderId(), o.handoverCode());
            }
        }
        List<PreorderEventPayload.OrderRef> refs = orders.findByWindow(w.id()).stream()
                .filter(PreorderOrder::counts)
                .map(o -> new PreorderEventPayload.OrderRef(o.orderId(), o.customerId(), o.fulfilment().name(),
                        codes.get(o.orderId())))
                .toList();
        events.commsDueTo(w, CommKind.OUT_FOR_DELIVERY, now, settings.settings().payment(), now, refs);
        LOG.info("Pre-order {}: run {} is out for delivery, {} customer(s) told", w.code(), run.id(), refs.size());
        return Optional.of(marker);
    }
}
