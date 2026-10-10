package com.afrotransact.trustops.application;

import java.time.Instant;

import com.afrotransact.trustops.domain.ModerationItem;

/** A queue item with its live SLA state. */
public record ModerationItemView(ModerationItem item, SlaView sla) {

    static ModerationItemView of(ModerationItem item, Instant now) {
        return new ModerationItemView(item, SlaView.of(item.sla(), now));
    }
}
