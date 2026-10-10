package com.afrotransact.fulfilment.domain;

import java.util.EnumSet;
import java.util.Set;

/** Problems at a seller pickup stop and the resolutions allowed for each. */
public enum PickupProblemType {
    NOT_READY(false, EnumSet.of(PickupResolution.WAIT, PickupResolution.SKIP)),
    CLOSED(false, EnumSet.of(PickupResolution.SKIP)),
    WRONG_COUNT(true, EnumSet.of(PickupResolution.PARTIAL, PickupResolution.SKIP)),
    DAMAGED(true, EnumSet.of(PickupResolution.SKIP, PickupResolution.PARTIAL));

    private final boolean photoRequired;
    private final Set<PickupResolution> resolutions;

    PickupProblemType(boolean photoRequired, Set<PickupResolution> resolutions) {
        this.photoRequired = photoRequired;
        this.resolutions = resolutions;
    }

    public boolean photoRequired() {
        return photoRequired;
    }

    public Set<PickupResolution> resolutions() {
        return EnumSet.copyOf(resolutions);
    }
}
