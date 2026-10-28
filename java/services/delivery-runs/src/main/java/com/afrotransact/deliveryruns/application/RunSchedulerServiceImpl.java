package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.CutoffDecision;
import com.afrotransact.deliveryruns.domain.CutoffDecisionPolicy;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RunSchedulerServiceImpl implements RunSchedulerService {

    private static final Logger LOG = LoggerFactory.getLogger(RunSchedulerServiceImpl.class);

    private final RunRepository runs;
    private final LocationRepository locations;
    private final RunLifecycle lifecycle;
    private final DeliveryRunsEventPublisher events;
    private final CutoffDecisionPolicy policy;
    private final DeliveryRunsSettings settings;
    private final Clock clock;

    RunSchedulerServiceImpl(RunRepository runs, LocationRepository locations, RunLifecycle lifecycle,
                            DeliveryRunsEventPublisher events, CutoffDecisionPolicy policy,
                            DeliveryRunsSettings settings, Clock clock) {
        this.runs = runs;
        this.locations = locations;
        this.lifecycle = lifecycle;
        this.events = events;
        this.policy = policy;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> dueToOpen(int limit) {
        return runs.dueToOpen(Instant.now(clock), limit);
    }

    @Override
    @Transactional
    public boolean processOpen(UUID runId) {
        Instant now = Instant.now(clock);
        Optional<Run> locked = runs.tryLock(runId).filter(r -> r.dueToOpen(now));
        if (locked.isEmpty()) {
            return false;
        }
        Run run = locked.get();
        lifecycle.moved(location(run), run.open(now), run.status(), DeliveryRunsEvents.RUN_OPENED, false);
        LOG.info("Run {} opened automatically", runId);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> dueForReminder(int limit) {
        return runs.dueForReminder(Instant.now(clock), settings.reminderLead(), limit);
    }

    @Override
    @Transactional
    public boolean processReminder(UUID runId) {
        Instant now = Instant.now(clock);
        Optional<Run> locked = runs.tryLock(runId).filter(r -> r.dueForReminder(now, settings.reminderLead()));
        if (locked.isEmpty()) {
            return false;
        }
        Run saved = runs.update(locked.get().reminderSent(now));
        events.run(DeliveryRunsEvents.RUN_CUTOFF_REMINDER, location(saved), saved, saved.status(), null, null, null);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> dueForCutoff(int limit) {
        return runs.dueForCutoff(Instant.now(clock), limit);
    }

    @Override
    @Transactional
    public boolean processCutoff(UUID runId) {
        Instant now = Instant.now(clock);
        Optional<Run> locked = runs.tryLock(runId).filter(r -> r.dueForCutoff(now));
        if (locked.isEmpty()) {
            return false;
        }
        Run run = locked.get();
        RunStatus previous = run.status();
        Location location = location(run);
        Run cutOff = run.cutOff(now);
        CutoffDecision decision = policy.decide(cutOff, now);
        switch (decision.outcome()) {
            case CONFIRM -> lifecycle.confirm(location, cutOff, previous, now, RunDecision.system(null));
            case EXTEND -> lifecycle.extended(location, cutOff.extendByPolicy(now), previous, run.terms().cutoffAt(),
                    RunDecision.system(decision.reason()), now);
            case CANCEL -> lifecycle.cancel(location, cutOff, RunDecision.system(decision.reason()), now);
            default -> throw new IllegalStateException("Unknown outcome " + decision.outcome());
        }
        LOG.info("Run {} cut off with {} of {} orders: {}", runId, run.reservedOrders(), run.terms().minOrders(),
                decision.outcome());
        return true;
    }

    private Location location(Run run) {
        return locations.findById(run.locationId()).orElseThrow();
    }
}
