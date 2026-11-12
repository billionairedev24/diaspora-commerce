package com.afrotransact.catalog.adapter.in.scheduling;

import com.afrotransact.catalog.application.ImageQualityService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The back-fill of S392: one batch of listings per run (the rate limit is the batch size per interval), then the hide
 * step, which does nothing unless its own flag is on. Several pods may run it; scores are replaced, never added, so a
 * listing measured twice is harmless. A restart resumes by itself because the next batch is whatever is not scored yet.
 */
@Component
class ImageQualityJob {

    private final ImageQualityService quality;

    ImageQualityJob(ImageQualityService quality) {
        this.quality = quality;
    }

    @Scheduled(fixedDelayString = "${at.catalog.images.rescore-every:30s}",
            initialDelayString = "${at.catalog.images.initial-delay:45s}")
    void run() {
        quality.rescoreBatch();
        quality.hideDue();
    }
}
