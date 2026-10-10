package com.afrotransact.ai.domain.guardrail;

/** Replaces emails, phone numbers, payment cards (Luhn-valid), SSNs and street addresses with placeholders. */
public interface PiiRedactor {

    Redaction redact(String text);

    /** Redacts {@code text} reusing the placeholders already assigned in {@code previous} (same value, same tag). */
    Redaction redact(String text, Redaction previous);
}
