package com.afrotransact.offers.domain;

import java.util.Map;
import java.util.TreeMap;

/** Thrown when an offer or coupon breaks a rule; carries per-field messages for the seller form. */
public class OfferRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, String> fieldErrors;

    public OfferRuleViolation(String message) {
        this(message, Map.of());
    }

    public OfferRuleViolation(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return new TreeMap<>(fieldErrors);
    }
}
