package com.afrotransact.seller.domain;

/** Escalation targets of one stage: level 1 first, level 2 when level 1 did not help. */
public record EscalationChain(EscalationTarget level1, EscalationTarget level2) {

    public EscalationChain {
        level1 = level1 == null ? EscalationTarget.NONE : level1;
        level2 = level2 == null ? EscalationTarget.NONE : level2;
    }

    public EscalationTarget at(int level) {
        return level == 1 ? level1 : level == 2 ? level2 : EscalationTarget.NONE;
    }

    /** Level 2 without level 1 is a configuration mistake: the levels are in order. */
    public boolean inOrder() {
        return !level1.isEmpty() || level2.isEmpty();
    }
}
