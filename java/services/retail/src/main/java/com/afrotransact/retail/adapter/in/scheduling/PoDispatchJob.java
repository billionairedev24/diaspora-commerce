package com.afrotransact.retail.adapter.in.scheduling;

import com.afrotransact.retail.application.PoDispatchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers sent POs to suppliers (email via media + notifications, EDI 850) with retries; every replica runs it. */
@Component
@ConditionalOnProperty(prefix = "at.retail.dispatch", name = "job-enabled", havingValue = "true",
        matchIfMissing = true)
class PoDispatchJob {

    private final PoDispatchService dispatch;

    PoDispatchJob(PoDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @Scheduled(fixedDelayString = "${at.retail.dispatch.interval:30s}",
            initialDelayString = "${at.retail.dispatch.interval:30s}")
    void run() {
        dispatch.dispatchDue();
    }
}
