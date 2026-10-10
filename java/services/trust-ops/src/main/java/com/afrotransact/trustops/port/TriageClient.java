package com.afrotransact.trustops.port;

import java.util.Map;
import java.util.Optional;

import com.afrotransact.trustops.domain.TriageHint;

/** ai-gateway {@code POST /v1/ai/triage} (ai:triage). Advisory only; empty when AI triage is switched off. */
public interface TriageClient {

    Optional<TriageHint> triage(String subjectType, String subjectId, Map<String, String> fields);
}
