package com.afrotransact.deliveryruns.domain;

import java.time.Instant;

/** Decides a run's fate at cutoff: minimum met → confirm; otherwise the run's {@link CutoffPolicy}. */
public interface CutoffDecisionPolicy {

    /** @param now when the cutoff is processed (an extension must still end in the future) */
    CutoffDecision decide(Run run, Instant now);
}
