package com.afrotransact.orders.adapter.in.scheduling;

import com.afrotransact.orders.application.LatePreorderService;
import com.afrotransact.orders.application.PreorderDeliveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * S272 pre-order jobs: flag PREORDER shipments that missed their delivery date (orders.preorder.delivery_missed) and
 * void late adds whose payment link expired unpaid. Both are idempotent (row locks, state checks), so several replicas
 * are safe.
 */
@Component
@ConditionalOnProperty(prefix = "at.orders.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class PreorderJobs {

    private final PreorderDeliveryService deliveries;
    private final LatePreorderService lateAdds;

    PreorderJobs(PreorderDeliveryService deliveries, LatePreorderService lateAdds) {
        this.deliveries = deliveries;
        this.lateAdds = lateAdds;
    }

    @Scheduled(fixedDelayString = "${at.orders.preorders.missed-delivery-interval:15m}",
            initialDelayString = "${at.orders.preorders.initial-delay:1m}")
    void missedDeliveries() {
        deliveries.flagMissedDeliveries();
    }

    @Scheduled(fixedDelayString = "${at.orders.preorders.link-expiry-interval:5m}",
            initialDelayString = "${at.orders.preorders.initial-delay:1m}")
    void expiredPaymentLinks() {
        lateAdds.expireUnpaid();
    }
}
