package com.afrotransact.deliveryruns.domain;

/**
 * What to do with a run whose cutoff has passed.
 *
 * @param outcome confirm, extend or cancel
 * @param reason  why (for EXTEND and CANCEL: {@link Run#REASON_MINIMUM_NOT_MET})
 */
public record CutoffDecision(Outcome outcome, String reason) {

    /** Cutoff outcomes. */
    public enum Outcome {
        CONFIRM,
        EXTEND,
        CANCEL
    }

    public static CutoffDecision confirm() {
        return new CutoffDecision(Outcome.CONFIRM, null);
    }

    public static CutoffDecision extend(String reason) {
        return new CutoffDecision(Outcome.EXTEND, reason);
    }

    public static CutoffDecision cancel(String reason) {
        return new CutoffDecision(Outcome.CANCEL, reason);
    }
}
