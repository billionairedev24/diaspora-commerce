package com.afrotransact.content.domain.market;

/**
 * Status of a state or city in Markets &amp; locations. LIVE takes orders; PLANNED is on the roadmap (shown in the
 * back office, not yet serving); OFF is switched off and hidden from the default views.
 */
public enum LocationStatus {
    LIVE,
    PLANNED,
    OFF
}
