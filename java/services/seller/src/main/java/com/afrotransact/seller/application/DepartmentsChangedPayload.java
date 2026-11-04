package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Payload of seller.org.departments_changed: staff replaced an organisation's departments. Department codes are the
 * catalog node codes; added and removed are what actually changed.
 *
 * @param actorId the staff member
 * @param reason  why (at least 10 characters)
 */
public record DepartmentsChangedPayload(String orgId, List<String> before, List<String> after, List<String> added,
                                        List<String> removed, String actorId, String reason, Instant occurredAt) {

    public DepartmentsChangedPayload {
        before = List.copyOf(before);
        after = List.copyOf(after);
        added = List.copyOf(added);
        removed = List.copyOf(removed);
    }

    static DepartmentsChangedPayload of(String orgId, Set<String> before, Set<String> after, String actorId,
                                        String reason, Instant at) {
        return new DepartmentsChangedPayload(orgId, sorted(before), sorted(after),
                sorted(after.stream().filter(c -> !before.contains(c)).toList()),
                sorted(before.stream().filter(c -> !after.contains(c)).toList()), actorId, reason, at);
    }

    private static List<String> sorted(Collection<String> codes) {
        return codes.stream().sorted().toList();
    }
}
