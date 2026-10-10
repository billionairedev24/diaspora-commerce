package com.afrotransact.fulfilment.domain;

import java.util.EnumSet;
import java.util.Set;

/** What went wrong at a drop-off, with the resolutions the driver may choose and the evidence required. */
public enum DeliveryProblemType {
    CUSTOMER_UNAVAILABLE(false, false, EnumSet.of(ProblemResolution.REATTEMPT, ProblemResolution.PICKUP_POINT,
            ProblemResolution.RETURN_TO_FC)),
    ACCESS_ISSUE(true, true, EnumSet.of(ProblemResolution.REATTEMPT, ProblemResolution.RETURN_TO_FC)),
    DAMAGED(true, true, EnumSet.of(ProblemResolution.RETURN_TO_FC, ProblemResolution.ACCEPTED_WITH_DAMAGE)),
    REFUSED(false, true, EnumSet.of(ProblemResolution.RETURN_TO_FC)),
    MISSING(false, true, EnumSet.of(ProblemResolution.REPORT_MISSING)),
    UNSAFE(true, true, EnumSet.of(ProblemResolution.RETURN_TO_FC, ProblemResolution.PICKUP_POINT));

    private final boolean photoRequired;
    private final boolean noteRequired;
    private final Set<ProblemResolution> resolutions;

    DeliveryProblemType(boolean photoRequired, boolean noteRequired, Set<ProblemResolution> resolutions) {
        this.photoRequired = photoRequired;
        this.noteRequired = noteRequired;
        this.resolutions = resolutions;
    }

    public boolean photoRequired() {
        return photoRequired;
    }

    public boolean noteRequired() {
        return noteRequired;
    }

    public Set<ProblemResolution> resolutions() {
        return EnumSet.copyOf(resolutions);
    }
}
