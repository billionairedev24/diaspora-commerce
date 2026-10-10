package com.afrotransact.deliveryruns.adapter.in.scheduling;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import com.afrotransact.deliveryruns.application.RunSchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every {@code at.delivery-runs.jobs.interval}: opens ANNOUNCED runs whose {@code opensAt} came, sends the 24-hour
 * cutoff reminders, then decides runs past their cutoff (confirm / extend / cancel). Safe on every replica: each run
 * is processed in its own transaction under {@code FOR UPDATE SKIP LOCKED}, and a failing run is logged and retried
 * on the next tick without blocking the others.
 */
@Component
@ConditionalOnProperty(prefix = "at.delivery-runs.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RunScheduleJob {

    private static final Logger LOG = LoggerFactory.getLogger(RunScheduleJob.class);

    private final RunSchedulerService scheduler;
    private final int batchSize;

    RunScheduleJob(RunSchedulerService scheduler, @Value("${at.delivery-runs.jobs.batch-size:50}") int batchSize) {
        this.scheduler = scheduler;
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
