package com.afrotransact.ai.application;

import java.util.List;
import java.util.Map;

/**
 * Trust-team triage: a short risk summary of a seller/provider application or a listing, where every flag cites the
 * input fields it is based on. Humans make the decision; the summary is advisory only.
 */
public interface TriageService {

    Report triage(Caller caller, Request request);

    /**
     * Triage input.
     *
     * @param subjectType {@code APPLICATION} or {@code LISTING}
     * @param subjectId   application or product id
     * @param fields      field name → value as shown to reviewers (already masked where required)
     */
    record Request(String subjectType, String subjectId, Map<String, String> fields) {
    }

    /**
     * Triage summary.
     *
     * @param subjectType      what was triaged
     * @param subjectId        its id
     * @param summary          two or three sentences
     * @param riskLevel        LOW, MEDIUM or HIGH
     * @param flags            findings, each citing input fields
     * @param citations        the cited fields with the (redacted) values the reviewer should look at
     * @param droppedFlags     flags removed because they cited no real field
     * @param decision         always {@code HUMAN_REVIEW_REQUIRED}
     * @param model            model that wrote it
     */
    record Report(String subjectType, String subjectId, String summary, String riskLevel, List<Flag> flags,
                  List<Citation> citations, int droppedFlags, String decision, String model) {
    }

    /** A finding such as {@code COUNTERFEIT_RISK} with the fields it is based on. */
    record Flag(String code, String description, List<String> citedFields) {
    }

    /** A cited input field. */
    record Citation(String field, String excerpt) {
    }
}
