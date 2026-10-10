package com.afrotransact.trustops.application;

import java.time.Instant;

import com.afrotransact.trustops.domain.SupportCase;
import com.afrotransact.trustops.domain.SupportCaseStatus;

/** A support case with its first-response SLA (null once someone picked it up). */
public record SupportCaseView(SupportCase supportCase, SlaView sla) {

    static SupportCaseView of(SupportCase c, Instant now) {
        return new SupportCaseView(c, c.status() == SupportCaseStatus.OPEN ? SlaView.of(c.sla(), now) : null);
    }
}
