package com.afrotransact.ai.domain.guardrail;

import java.util.List;

import com.afrotransact.ai.domain.lola.ProductCard;

/**
 * Model text after grounding.
 *
 * @param text    the kept text (sentences with invented products or prices removed)
 * @param cards   cards for the ATINs the kept text mentions, built from tool results, in order of mention
 * @param dropped what was removed and why
 */
public record GroundingResult(String text, List<ProductCard> cards, List<DroppedClaim> dropped) {

    public GroundingResult {
        cards = List.copyOf(cards);
        dropped = List.copyOf(dropped);
    }

    /**
     * A claim that no tool result supports.
     *
     * @param kind  {@code ATIN} or {@code PRICE}
     * @param value the invented ATIN or the price as written
     */
    public record DroppedClaim(String kind, String value) {
    }
}
