package com.afrotransact.platform.security.relationship;

import java.util.List;

/**
 * Port: asks the relationship store (OpenFGA) whether {@code user} has {@code relation} on {@code object}.
 * Only present when {@code at.security.fga.enabled=true}; most services use {@link ResourceAccessChecker} instead.
 */
public interface RelationshipChecker {

    /**
     * @param contextualTuples tuples that hold for this check only, typically the resource's owner read from the
     *                         service's own database ({@link RelationshipTuple#ownedBy})
     * @throws RelationshipCheckUnavailableException when the store cannot answer (timeouts, 5xx)
     */
    boolean check(String user, String relation, String object, List<RelationshipTuple> contextualTuples);

    default boolean check(String user, String relation, String object) {
        return check(user, relation, object, List.of());
    }
}
