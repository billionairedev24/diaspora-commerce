package com.afrotransact.ai.domain;

/**
 * Outcome of a budget check.
 *
 * @param verdict allow, degrade to the cheapest model, or deny
 * @param scope   which limit decided it ({@code none}, {@code user-tokens}, {@code user-cost}, {@code feature-cost})
 * @param detail  human-readable explanation (no personal data)
 */
public record BudgetDecision(Verdict verdict, String scope, String detail) {

    public static final BudgetDecision ALLOW = new BudgetDecision(Verdict.ALLOW, "none", "Within budget");

    public boolean degrade() {
        return verdict == Verdict.DEGRADE;
    }

    public boolean denied() {
        return verdict == Verdict.DENY;
    }

    /** Budget verdict. */
    public enum Verdict {
        ALLOW,
        DEGRADE,
        DENY
    }
}
