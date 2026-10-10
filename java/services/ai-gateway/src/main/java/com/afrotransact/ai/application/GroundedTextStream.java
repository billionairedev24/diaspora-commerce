package com.afrotransact.ai.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.ai.domain.guardrail.GroundingFacts;
import com.afrotransact.ai.domain.guardrail.GroundingResult;
import com.afrotransact.ai.domain.guardrail.GroundingVerifier;
import com.afrotransact.ai.domain.lola.ProductCard;
import com.afrotransact.ai.port.OutputModerator;

/**
 * Buffers streamed model text until a sentence is complete, then grounds and moderates it before it is emitted.
 * The shopper still sees the answer sentence by sentence, but nothing ungrounded is ever sent.
 */
final class GroundedTextStream {

    private final GroundingVerifier verifier;
    private final GroundingFacts facts;
    private final OutputModerator moderator;
    private final StringBuilder buffer = new StringBuilder();
    private final Map<String, ProductCard> cards = new LinkedHashMap<>();
    private final List<GroundingResult.DroppedClaim> dropped = new ArrayList<>();
    private int moderated;

    GroundedTextStream(GroundingVerifier verifier, GroundingFacts facts, OutputModerator moderator) {
        this.verifier = verifier;
        this.facts = facts;
        this.moderator = moderator;
    }

    /** Adds a delta and returns the grounded text that is ready to emit (possibly empty). */
    String accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        buffer.append(delta);
        int end = verifier.completeSentencesEnd(buffer.toString());
        if (end == 0) {
            return "";
        }
        String ready = buffer.substring(0, end);
        buffer.delete(0, end);
        return process(ready);
    }

    /** Flushes whatever is left at the end of the model's answer. */
    String finish() {
        String rest = buffer.toString();
        buffer.setLength(0);
        return rest.isEmpty() ? "" : process(rest);
    }

    List<ProductCard> cards() {
        return List.copyOf(cards.values());
    }

    List<GroundingResult.DroppedClaim> dropped() {
        return List.copyOf(dropped);
    }

    int moderated() {
        return moderated;
    }

    private String process(String text) {
        GroundingResult result = verifier.verify(text, facts);
        result.cards().forEach(c -> cards.putIfAbsent(c.atin(), c));
        dropped.addAll(result.dropped());
        if (result.text().isBlank()) {
            return result.text();
        }
        OutputModerator.Verdict verdict = moderator.review(result.text());
        if (!verdict.allowed()) {
            moderated++;
            return verdict.replacement();
        }
        return result.text();
    }
}
