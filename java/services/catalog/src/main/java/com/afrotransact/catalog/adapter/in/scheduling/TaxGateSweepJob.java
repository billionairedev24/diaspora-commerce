package com.afrotransact.catalog.adapter.in.scheduling;

import com.afrotransact.catalog.application.TaxGateSweepService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Closes any live listing without a resolved tax decision, reopens resolved ones and announces migration closures. */
@Component
class TaxGateSweepJob {

    private final TaxGateSweepService sweep;

    TaxGateSweepJob(TaxGateSweepService sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${at.catalog.tax-gate.sweep-interval:60s}",
            initialDelayString = "${at.catalog.tax-gate.sweep-initial-delay:20s}")
    void run() {
        sweep.sweep();
    }
}
