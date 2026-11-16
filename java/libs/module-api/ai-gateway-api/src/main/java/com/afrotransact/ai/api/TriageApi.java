package com.afrotransact.ai.api;

import java.util.List;
import java.util.Map;

/**
 * The trust-team triage summary trust-ops asks for over HTTP today ({@code POST /v1/ai/triage}, scope {@code ai:triage}), entered
 * in-process under the calling module's principal. It is the same use case, so the per-feature AI budget (the USD cap per day), the
 * rate limit and the model fallback apply exactly as for a token; a spent budget is 429 {@code ai.budget-exceeded}, no model 503
 * {@code ai.model-unavailable}, a caller without the scope 403. Advisory only.
 */
public interface TriageApi {

    /**
     * @param subjectType {@code APPLICATION} or {@code LISTING}
     * @param fields      field name to value as shown to reviewers
     */
    Report triage(String subjectType, String subjectId, Map<String, String> fields);

    /** The summary: risk level, two or three sentences, and flags that each cite input fields. */
    record Report(String riskLevel, String summary, List<Flag> flags, String model) {

        public Report {
            flags = flags == null ? List.of() : List.copyOf(flags);
        }
    }

    record Flag(String code, String description, List<String> citedFields) {

        public Flag {
            citedFields = citedFields == null ? List.of() : List.copyOf(citedFields);
        }
    }
}
