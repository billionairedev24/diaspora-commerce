package com.afrotransact.content.adapter.in.scheduling;

import com.afrotransact.content.application.PlacementLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Moves placements live / ended on schedule; each pass is idempotent and safe on several replicas. */
@Component
@ConditionalOnProperty(prefix = "at.content.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class ContentJobs {

    private static final Logger LOG = LoggerFactory.getLogger(ContentJobs.class);

    private final PlacementLifecycleService lifecycle;

    ContentJobs(PlacementLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${at.content.jobs.placement-lifecycle-interval:60s}", initialDelayString = "15s")
    void advancePlacements() {
        int changed = lifecycle.advance();
        if (changed > 0) {
            LOG.info("Placement lifecycle changed {} placement(s)", changed);
        }
    }
}
