package com.afrotransact.deliveryruns.adapter.in.scheduling;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import com.afrotransact.deliveryruns.application.AdvertorialLifecycle;
import com.afrotransact.deliveryruns.application.RunSchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every {@code at.delivery-runs.jobs.interval}: opens ANNOUNCED runs whose {@code opensAt} came, sends the 24-hour
 * cutoff reminders, then decides runs past their cutoff (confirm / extend / cancel), then ends the advertorials of runs and windows
 * that stopped taking orders (S263). Safe on every replica: each run
 * is processed in its own transaction under {@code FOR UPDATE SKIP LOCKED}, and a failing run is logged and retried
 * on the next tick without blocking the others.
 */
@Component
@ConditionalOnProperty(prefix = "at.delivery-runs.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RunScheduleJob {

    private static final Logger LOG = LoggerFactory.getLogger(RunScheduleJob.class);

    private static final int ADVERTORIAL_BATCH = 1000;

    private final RunSchedulerService scheduler;
    private final AdvertorialLifecycle advertorials;
    private final int batchSize;

    RunScheduleJob(RunSchedulerService scheduler, AdvertorialLifecycle advertorials,
                   @Value("${at.delivery-runs.jobs.batch-size:50}") int batchSize) {
        this.scheduler = scheduler;
        this.advertorials = advertorials;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${at.delivery-runs.jobs.interval:60s}",
            initialDelayString = "${at.delivery-runs.jobs.initial-delay:15s}")
    public void tick() {
        int opened = each("open", scheduler.dueToOpen(batchSize), scheduler::processOpen);
        int reminded = each("reminder", scheduler.dueForReminder(batchSize), scheduler::processReminder);
        int decided = each("cutoff", scheduler.dueForCutoff(batchSize), scheduler::processCutoff);
        if (opened + reminded + decided > 0) {
            LOG.info("Run schedule: {} opened, {} reminders, {} cut off", opened, reminded, decided);
        }
        try {
            advertorials.sweep(ADVERTORIAL_BATCH);
        } catch (RuntimeException e) {
            LOG.warn("Advertorial sweep failed; retrying next tick", e);
        }
    }

    private static int each(String step, List<UUID> ids, Predicate<UUID> process) {
        int done = 0;
        for (UUID id : ids) {
            try {
                if (process.test(id)) {
                    done++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Run schedule {} failed for run {}; retrying next tick", step, id, e);
            }
        }
        return done;
    }
}
