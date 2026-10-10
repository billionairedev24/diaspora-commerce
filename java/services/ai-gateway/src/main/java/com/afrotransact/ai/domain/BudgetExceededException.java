package com.afrotransact.ai.domain;

/** A daily AI budget is exhausted (HTTP 429, code {@code ai.budget-exceeded}). */
public class BudgetExceededException extends RuntimeException {

    public static final String CODE = "ai.budget-exceeded";
    private static final long serialVersionUID = 1L;

    private final String feature;
    private final String scope;

    public BudgetExceededException(String feature, String scope, String message) {
        super(message);
        this.feature = feature;
        this.scope = scope;
    }

    public String feature() {
        return feature;
    }

    public String scope() {
        return scope;
    }
}
