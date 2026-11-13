package com.afrotransact.catalog.port;

import java.util.List;

/**
 * The search index (Meilisearch or Elasticsearch, whichever search-service runs) asked "which pages look like this name?".
 * It only recalls; catalog ranks the answer on its own data, so a stale index can never show a page that is not live.
 */
public interface CandidateRecall {

    /**
     * Product ATINs in the index's own order of relevance, at most {@code limit}.
     *
     * @throws RecallUnavailable when the index cannot be asked: the caller answers "no suggestions", never a guess
     */
    List<String> recall(String text, int limit);

    /** The search index did not answer. */
    class RecallUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RecallUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
