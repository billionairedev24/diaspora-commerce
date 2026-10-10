package com.afrotransact.trustops.domain;

import java.util.Map;
import java.util.Optional;

/** Decides whether a consumed event is audit-worthy and extracts actor, action and entity from its payload. */
public interface AuditClassifier {

    /**
     * @param eventType event type header, for example {@code identity.role.granted}
     * @param payload   the event payload as a JSON object
     * @param key       Kafka message key (aggregate id), used when the payload names no entity
     * @return the audit facts, or empty when the event is not a privileged action (for example a shipment status
     *         change made by the carrier rather than staff)
     */
    Optional<AuditFacts> classify(String eventType, Map<String, Object> payload, String key);
}
