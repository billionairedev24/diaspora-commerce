package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.domain.DepartmentRequest;
import com.afrotransact.seller.domain.DepartmentRequestStatus;

/** The departments each organisation may sell in, and the requests to add one. */
public interface CatalogScopeRepository {

    /** The organisation's department codes (empty when none are recorded). */
    Set<String> departments(String orgId);

    /** Adds departments that are not there yet; existing ones keep their source. */
    void add(String orgId, Set<String> departments, String source, String actor, Instant at);

    /** Replaces the set (staff): drops the ones not listed, adds the new ones. */
    void replace(String orgId, Set<String> departments, String source, String actor, Instant at);

    DepartmentRequest insert(DepartmentRequest request);

    DepartmentRequest update(DepartmentRequest request);

    Optional<DepartmentRequest> findRequest(UUID id);

    Optional<DepartmentRequest> findPending(String orgId, String departmentCode);

    List<DepartmentRequest> requestsOf(String orgId);

    List<DepartmentRequest> queue(DepartmentRequestStatus status, int limit);
}
