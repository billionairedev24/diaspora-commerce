package com.afrotransact.ai.domain.guardrail;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Text with personal data replaced by placeholders such as {@code [EMAIL_1]}. The mapping lives only in memory for
 * the current request, so a model answer can be restored for the user's own display; it is never logged or stored.
 *
 * @param text         redacted text (safe for prompts and logs)
 * @param placeholders placeholder → original value
 * @param counts       number of distinct values redacted per type
 */
public record Redaction(String text, Map<String, String> placeholders, Map<PiiType, Integer> counts) {

    public Redaction {
        placeholders = Map.copyOf(placeholders);
        counts = counts.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(counts));
    }

    public static Redaction none(String text) {
        return new Redaction(text, Map.of(), Map.of());
    }

    public boolean redactedAnything() {
        return !placeholders.isEmpty();
    }

    /** Puts the original values back into {@code modelText} (only for display to the user who supplied them). */
    public String restore(String modelText) {
        if (modelText == null || placeholders.isEmpty()) {
            return modelText;
        }
        String out = modelText;
        for (Map.Entry<String, String> e : new LinkedHashMap<>(placeholders).entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }
}
