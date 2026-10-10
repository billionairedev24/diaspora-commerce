package com.afrotransact.trustops.application;

import java.time.Instant;

import com.afrotransact.trustops.domain.Sla;
import com.afrotransact.trustops.domain.SlaState;

/** An SLA timer as shown in the queues: due time, state and seconds left or overdue. */
public record SlaView(Instant startedAt, Instant dueAt, SlaState state, long remainingSeconds, long overdueSeconds) {

    public static SlaView of(Sla sla, Instant now) {
        return new SlaView(sla.startedAt(), sla.dueAt(), sla.state(now), sla.remaining(now).toSeconds(),
                sla.overdue(now).toSeconds());
    }
}
