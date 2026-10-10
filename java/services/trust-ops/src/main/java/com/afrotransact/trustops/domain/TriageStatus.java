package com.afrotransact.trustops.domain;

/** Whether an AI triage hint is attached. UNAVAILABLE = AI off or failed; the queue works the same (fail-open). */
public enum TriageStatus {
    COMPLETED,
    UNAVAILABLE
}
