package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.UUID;

/** What sellers answered on the search-first step: sold an existing page, or said "None of these". */
public interface MatchDecisionRepository {

    void insert(Decision decision);

    /** How many "None of these" answers the organisation gave since the instant. */
    long countNoneSince(String sellerOrgId, Instant since);

    /**
     * @param choice          SELL_EXISTING or NONE_OF_THESE
     * @param query           what the seller typed
     * @param categoryId      the category on the screen when the seller answered; may be null
     * @param atin            SELL_EXISTING: the variant chosen; null otherwise
     * @param shown           the pages shown, best first, as text: {@code ATIN:score:KIND} separated by commas
     * @param likelyDuplicateOf NONE_OF_THESE: the page shown with a likely score, so staff can compare; else null
     * @param reviewState     OPEN for a likely duplicate, NONE otherwise
     */
    record Decision(UUID id, String sellerOrgId, String choice, String query, UUID categoryId, String atin, String shown,
                    String likelyDuplicateOf, String reviewState, Instant decidedAt) {
    }
}
