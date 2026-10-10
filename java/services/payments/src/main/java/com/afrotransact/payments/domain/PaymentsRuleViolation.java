package com.afrotransact.payments.domain;

import java.util.Map;

/** A payments rule was broken (HTTP 422). Carries a stable code such as {@code payments.payout-below-minimum}. */
public class PaymentsRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Map<String, String> fieldErrors;

    public PaymentsRuleViolation(String code, String message) {
        this(code, message, Map.of());
    }

    public PaymentsRuleViolation(String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public String code() {
        return code;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
