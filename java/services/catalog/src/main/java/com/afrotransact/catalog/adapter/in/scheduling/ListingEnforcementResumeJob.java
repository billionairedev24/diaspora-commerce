package com.afrotransact.catalog.adapter.in.scheduling;

import com.afrotransact.catalog.application.ListingVisibilityService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Picks up paged hide / restore runs a crashed or restarted pod left unfinished (S216). */
@Component
class ListingEnforcementResumeJob {

    private final ListingVisibilityService visibility;

    ListingEnforcementResumeJob(ListingVisibilityService visibility) {
        this.visibility = visibility;
    }

    @Scheduled(fixedDelayString = "${at.catalog.listing-enforcement.resume-interval:60s}",
            initialDelayString = "${at.catalog.listing-enforcement.resume-interval:60s}")
    void resume() {
        visibility.resumeStalled();
    }
}
