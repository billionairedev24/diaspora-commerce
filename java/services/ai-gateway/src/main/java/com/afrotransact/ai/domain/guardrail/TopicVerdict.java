package com.afrotransact.ai.domain.guardrail;

/**
 * Topic check of a shopper message before any model sees it.
 *
 * @param category what was detected
 * @param reply    Lola's fixed answer when the message is not passed to the model (empty for {@link Category#ALLOWED})
 */
public record TopicVerdict(Category category, String reply) {

    public static final TopicVerdict ALLOWED = new TopicVerdict(Category.ALLOWED, "");

    public boolean allowed() {
        return category == Category.ALLOWED;
    }

    /** Topic categories Lola treats specially. */
    public enum Category {
        ALLOWED,
        /** Diagnosis, dosage, treatment: Lola does not give medical advice. */
        MEDICAL,
        /** Legal, immigration or tax-law questions: Lola does not give legal advice. */
        LEGAL,
        /** The shopper asked for a person: hand off to support. */
        HUMAN_REQUESTED,
        /** Harmful requests: refuse; self-harm gets crisis resources and a human hand-off. */
        UNSAFE
    }
}
