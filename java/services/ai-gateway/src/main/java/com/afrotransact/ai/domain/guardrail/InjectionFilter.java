package com.afrotransact.ai.domain.guardrail;

/**
 * Neutralises untrusted text (seller titles and descriptions, provider bios, reviews) before a model reads it:
 * instruction-like phrases are removed and the content is quoted inside an {@code <untrusted>} envelope so the system
 * prompt can tell the model to treat it as data only.
 */
public interface InjectionFilter {

    /** Strips instruction-like patterns, control characters and role markers; truncates to {@code maxLength}. */
    FilteredText sanitize(String untrusted, int maxLength);

    /** Wraps already-sanitised content: {@code <untrusted source="...">…</untrusted>}. */
    String quote(String source, String sanitizedContent);

    /** Result of sanitising one value. */
    record FilteredText(String text, int removed) {
    }
}
