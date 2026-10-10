package com.afrotransact.ai.domain;

/** Invalid AI request (HTTP 422) with a stable code such as {@code ai.input-too-long}. */
public class AiRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public AiRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
