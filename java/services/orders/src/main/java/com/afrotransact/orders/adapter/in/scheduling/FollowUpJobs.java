package com.afrotransact.orders.adapter.in.scheduling;

import com.afrotransact.orders.application.FollowUpService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs capture / settlement / refund follow-ups every few seconds (several replicas are safe: SKIP LOCKED leases). */
@Component
@ConditionalOnProperty(prefix = "at.orders.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class FollowUpJobs {

    private final FollowUpService followUps;

    FollowUpJobs(FollowUpService followUps) {
        this.followUps = followUps;
    }

    @Scheduled(fixedDelayString = "${at.orders.jobs.follow-up-interval:5s}")
    void run() {
        followUps.runDue();
    }
}
