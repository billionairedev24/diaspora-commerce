package com.afrotransact.deliveryruns.domain;

import java.util.List;

/** Publishing was refused: one {@link RuleViolation} per failed publish check (HTTP 422). */
public class PublishBlockedException extends RuleViolation {

    private static final long serialVersionUID = 1L;

    private final transient List<RuleViolation> violations;

    public PublishBlockedException(List<RuleViolation> violations) {
        super("delivery-runs.preorder-checks-failed", violations.size() + " check"
                + (violations.size() == 1 ? "" : "s") + " to fix before publishing");
        this.violations = List.copyOf(violations);
    }

    public List<RuleViolation> violations() {
        return violations;
    }
}
