package com.afrotransact.ai.port;

import java.time.Instant;
import java.util.List;

import com.afrotransact.ai.domain.lola.ProductCard;

/**
 * Lola's conversation memory. Turns are stored redacted (placeholders instead of personal data) and scoped to the
 * owner: a session id of another subject returns nothing.
 */
public interface ConversationStore {

    /** The last {@code limit} turns of the session, oldest first. */
    List<Turn> recent(String sessionId, String subject, int limit);

    void append(String sessionId, String subject, List<Turn> turns);

    /**
     * One stored turn.
     *
     * @param role     {@code user} or {@code assistant}
     * @param content  redacted text
     * @param products product cards shown with an assistant turn (so later turns can refer to them)
     * @param at       when
     */
    record Turn(String role, String content, List<ProductCard> products, Instant at) {

        public Turn {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }
}
