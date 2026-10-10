package com.afrotransact.content.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.content.domain.flag.FlagEvaluation;

/**
 * Server-side flag evaluation for every client (web, mobile, Go and Java services), including through
 * OpenFeature's OFREP. Changes reach every replica within the cache TTL.
 */
public interface FlagEvaluationService {

    /**
     * Evaluates every flag (or only {@code keys} when not empty).
     *
     * @param subject        targeting key; the same subject always lands in the same rollout bucket
     * @param market         market
     * @param requestedRoles roles claimed by the caller; only trusted from service principals, otherwise the
     *                       authenticated caller's own roles are used (anonymous callers have none)
     * @param keys           flag keys to evaluate (empty = all)
     */
    FlagResults evaluateAll(String subject, String market, Set<String> requestedRoles, Collection<String> keys);

    /** One flag; empty when it does not exist. */
    Optional<FlagEvaluation> evaluate(String key, String subject, String market, Set<String> requestedRoles);

    /**
     * Evaluations with an ETag over the flag versions and the context.
     *
     * @param evaluations evaluations in key order
     * @param etag        fingerprint
     */
    record FlagResults(List<FlagEvaluation> evaluations, String etag) {
    }
}
