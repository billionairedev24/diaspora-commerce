package com.afrotransact.deliveryruns.application;

import com.afrotransact.deliveryruns.domain.RunAction;

/**
 * Who decided a run transition and why: a staff member (their subject, and the reason they gave) or the cutoff job.
 *
 * @param actor  staff subject, or {@link RunAction#SYSTEM}
 * @param reason why; required for a cancellation and a minimum override
 */
record RunDecision(String actor, String reason) {

    /** The cutoff job's own decision. */
    static RunDecision system(String reason) {
        return new RunDecision(RunAction.SYSTEM, reason);
    }
}
