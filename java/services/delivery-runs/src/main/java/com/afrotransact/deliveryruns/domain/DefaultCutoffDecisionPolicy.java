package com.afrotransact.deliveryruns.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Minimum met → CONFIRM. Otherwise {@link CutoffPolicy#EXTEND} extends once (a run that was already extended, by
 * staff or by policy, is cancelled, as is one whose extended cutoff would already be in the past) and
 * {@link CutoffPolicy#CANCEL} cancels.
 */
public class DefaultCutoffDecisionPolicy implements CutoffDecisionPolicy {

    @Override
    public CutoffDecision decide(Run run, Instant now) {
        if (run.minimumMet()) {
            return CutoffDecision.confirm();
        }
        Instant extendedCutoff = run.terms().cutoffAt().plus(Duration.ofDays(run.terms().extendDays()));
        if (run.terms().cutoffPolicy() == CutoffPolicy.EXTEND && run.extensions() == 0
                && extendedCutoff.isAfter(now)) {
            return CutoffDecision.extend(Run.REASON_MINIMUM_NOT_MET);
        }
        return CutoffDecision.cancel(Run.REASON_MINIMUM_NOT_MET);
    }
}
