package com.afrotransact.deliveryruns.domain;

/** Where an advertorial stands. Shown to visitors only while {@link #LIVE}, inside its schedule and its source. */
public enum AdvertorialStatus {
    /** Staff are still writing it. */
    DRAFT,
    /** May be shown. */
    LIVE,
    /** Staff switched it off for now. */
    PAUSED,
    /** Over for good; see {@link AdvertorialEnd}. */
    ENDED
}
