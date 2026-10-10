package com.afrotransact.ai.domain.guardrail;

/**
 * Lola may only present products and prices that came from tool results in this turn. Every ATIN and every money
 * amount in the answer is checked against {@link GroundingFacts}; a sentence that mentions an unknown ATIN or price is
 * dropped, and product cards are built only from tool results.
 */
public interface GroundingVerifier {

    GroundingResult verify(String modelText, GroundingFacts facts);

    /**
     * Index just after the last complete sentence in {@code text} (0 when there is none). Streaming callers verify and
     * emit text up to this index and keep the rest buffered.
     */
    int completeSentencesEnd(String text);
}
