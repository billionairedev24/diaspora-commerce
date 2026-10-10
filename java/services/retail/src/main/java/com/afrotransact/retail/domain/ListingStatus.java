package com.afrotransact.retail.domain;

/** 1P listing readiness: new items start as Draft; Live needs a linked catalog ATIN and a price. */
public enum ListingStatus {
    DRAFT,
    LIVE,
    PAUSED
}
