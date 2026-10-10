package com.afrotransact.trustops.application;

import com.afrotransact.trustops.domain.SellerHealthMetrics;
import com.afrotransact.trustops.port.OffersClient;
import com.afrotransact.trustops.port.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Sends seller performance to offers-service (S223); failures are logged and reported, never thrown. */
@Component
class PerformancePush {

    private static final Logger LOG = LoggerFactory.getLogger(PerformancePush.class);

    private final OffersClient offers;

    PerformancePush(OffersClient offers) {
        this.offers = offers;
    }

    /** @return true when offers accepted the update */
    boolean push(String orgId, SellerHealthMetrics metrics, boolean restricted) {
        try {
            offers.pushPerformance(orgId, metrics == null ? null : metrics.onTimeRate(),
                    metrics == null ? null : metrics.defectRate(), restricted);
            return true;
        } catch (UpstreamException e) {
            LOG.warn("Could not push performance of {} to offers ({}): {}", orgId, e.status(), e.getMessage());
            return false;
        }
    }
}
