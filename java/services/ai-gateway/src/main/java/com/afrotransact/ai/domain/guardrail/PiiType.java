package com.afrotransact.ai.domain.guardrail;

/** Kinds of personal data replaced by placeholders before text reaches a model or a log line. */
public enum PiiType {
    CARD,
    SSN,
    EMAIL,
    PHONE,
    ADDRESS
}
