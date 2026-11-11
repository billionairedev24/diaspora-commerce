package com.afrotransact.orders.adapter.in.scheduling;

import com.afrotransact.orders.application.DeliveryQuoteSweepService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Escalates late delivery quote requests and expires unused quotes (several replicas are safe: row locks). */
@Component
@ConditionalOnProperty(prefix = "at.orders.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class DeliveryQuoteJobs {

    private final DeliveryQuoteSweepService sweep;

    DeliveryQuoteJobs(DeliveryQuoteSweepService sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${at.orders.jobs.quote-escalation-interval:5m}")
    void escalate() {
        sweep.escalate();
    }

    @Scheduled(fixedDelayString = "${at.orders.jobs.quote-expiry-interval:1m}")
    void expire() {
        sweep.expire();
    }
}
