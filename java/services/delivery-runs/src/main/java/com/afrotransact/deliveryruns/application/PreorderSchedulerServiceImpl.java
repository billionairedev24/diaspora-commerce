package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.PaymentSettings;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import com.afrotransact.deliveryruns.port.PreorderStepLog;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PreorderSchedulerServiceImpl implements PreorderSchedulerService {

    static final String OPENED = "OPENED";
    static final String CLOSED = "CLOSED";
    static final String DELIVERED = "DELIVERED";
    static final Duration REMIND_LEAD = Duration.ofHours(48);
    static final Duration LAST_CALL_LEAD = Duration.ofHours(6);

    private static final Logger LOG = LoggerFactory.getLogger(PreorderSchedulerServiceImpl.class);

    private final PreorderWindowRepository windows;
    private final PreorderOrderRepository orders;
    private final PreorderSettingsRepository settings;
    private final PreorderStepLog steps;
    private final PreorderEventPublisher events;
    private final RunService runs;
    private final PreorderOptions options;
    private final Clock clock;

    PreorderSchedulerServiceImpl(PreorderWindowRepository windows, PreorderOrderRepository orders,
                                 PreorderSettingsRepository settings, PreorderStepLog steps,
                                 PreorderEventPublisher events, RunService runs, PreorderOptions options,
                                 Clock clock) {
        this.windows = windows;
        this.orders = orders;
        this.settings = settings;
        this.steps = steps;
        this.events = events;
        this.runs = runs;
        this.options = options;
        this.clock = clock;
    }

    static String comms(CommKind kind) {
        return "COMMS_" + kind.name();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> candidates(int limit) {
        Instant now = Instant.now(clock);
        return windows.findPublished().stream()
                .filter(w -> !now.isBefore(w.details().opensAt())
                        || !now.isBefore(w.details().closesAt().minus(REMIND_LEAD)))
                .filter(w -> !steps.done(w.id()).contains(DELIVERED))
                .map(PreorderWindow::id).limit(Math.max(limit, 1)).toList();
    }

    @Override
    @Transactional
    public int process(UUID windowId) {
        Optional<PreorderWindow> locked = windows.tryLock(windowId).filter(w -> !w.draft());
        if (locked.isEmpty()) {
            return 0;
        }
        PreorderWindow w = locked.get();
        Instant now = Instant.now(clock);
        Set<String> done = steps.done(w.id());
        PaymentSettings payment = settings.settings().payment();
        Instant opens = w.details().opensAt();
        Instant closes = w.details().closesAt();
        int n = 0;

        if (!now.isBefore(opens) && !done.contains(OPENED)) {
            steps.markDone(w.id(), OPENED, opens, now);
            if (w.active() && !now.isAfter(closes)) {
                events.window(DeliveryRunsEvents.PREORDER_WINDOW_OPENED, w, payment, now);
            }
            n++;
        }
        n += comms(w, CommKind.ANNOUNCE, opens, closes, done, payment, now);
        n += comms(w, CommKind.REMIND_48H, closes.minus(REMIND_LEAD), closes.minus(LAST_CALL_LEAD), done, payment,
                now);
        n += comms(w, CommKind.LAST_CALL_6H, closes.minus(LAST_CALL_LEAD), closes, done, payment, now);

        if (now.isAfter(closes) && !done.contains(CLOSED)) {
            steps.markDone(w.id(), CLOSED, closes, now);
            Optional<Run> run = runs.fromWindow(w.id());
            if (run.isPresent() && w.runId() == null) {
                w = windows.update(w.handedTo(run.get().id(), now));
            }
            events.window(DeliveryRunsEvents.PREORDER_WINDOW_CLOSED, w, payment, now);
            n++;
        }
        if (now.isAfter(closes) && !done.contains(comms(CommKind.CLOSED))
                && now.isBefore(closes.plus(Duration.ofDays(1)))) {
            if (w.active() && w.details().comms().contains(CommKind.CLOSED)) {
                steps.markDone(w.id(), comms(CommKind.CLOSED), closes, now);
                events.commsDue(w, CommKind.CLOSED, closes, payment, now, orders.findByWindow(w.id()));
                n++;
            }
        }
        if (!now.isBefore(w.deliveredAt()) && !done.contains(DELIVERED)) {
            steps.markDone(w.id(), DELIVERED, w.deliveredAt(), now);
            events.window(DeliveryRunsEvents.PREORDER_WINDOW_DELIVERED, w, payment, now);
            n++;
        }
        if (n > 0) {
            LOG.info("Pre-order {}: {} scheduled step(s) done", w.code(), n);
        }
        return n;
    }

    /**
     * Emits {@code preorder.comms.due} for {@code kind} once, between {@code due} and {@code until} (later it is
     * stale and skipped for good). A paused window, or one with the message switched off, sends nothing; a paused
     * one may still send after resuming while the message is not stale.
     */
    private int comms(PreorderWindow w, CommKind kind, Instant due, Instant until, Set<String> done,
                      PaymentSettings payment, Instant now) {
        String step = comms(kind);
        if (now.isBefore(due) || done.contains(step)) {
            return 0;
        }
        if (!now.isBefore(until)) {
            steps.markDone(w.id(), step, due, now);
            return 0;
        }
        if (!w.active() || !w.details().comms().contains(kind)) {
            return 0;
        }
        steps.markDone(w.id(), step, due, now);
        events.commsDue(w, kind, due, payment, now, null);
        return 1;
    }

    @Override
    @Transactional
    public int purgeExpired() {
        LocalDate before = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC).minusDays(options.retentionDays());
        int deleted = orders.deleteDeliveredBefore(before);
        if (deleted > 0) {
            LOG.info("Pre-order retention: deleted {} orders of windows delivered before {}", deleted, before);
        }
        return deleted;
    }
}
