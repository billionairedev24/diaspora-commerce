package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.DocumentAccess;
import com.afrotransact.seller.domain.FirstCharge;
import com.afrotransact.seller.domain.OnboardingStep;
import com.afrotransact.seller.domain.TimelineEntry;

/**
 * An application as one viewer may see it. The tax ID is never present in clear unless {@code pii == FULL}.
 *
 * @param taxIdMasked e.g. {@code •••-••-6789}
 * @param taxIdFull   formatted tax ID, only for {@link PiiLevel#FULL}
 */
public record ApplicationView(Application application, PiiLevel pii, String taxIdMasked, String taxIdFull,
                              List<OnboardingStep> steps, List<OnboardingStep> completedSteps,
                              FirstCharge firstCharge, String planName, List<DocumentRef> documents,
                              List<TimelineEntry> timeline, List<DocumentAccess> accessLog) {

    public ApplicationView {
        steps = List.copyOf(steps);
        completedSteps = List.copyOf(completedSteps);
        documents = List.copyOf(documents);
        timeline = List.copyOf(timeline);
        accessLog = List.copyOf(accessLog);
    }
}
