package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * seller-service's answer to "which departments may this organisation sell in?" (service token, scope
 * {@code sellers:catalog-scope.read}). The catalog enforces it; it never decides it.
 */
public interface SellerScopes {

    /** @throws ScopeUnavailable when seller-service cannot be asked: callers fail closed */
    OrgScope of(String orgId);

    /**
     * @param exempt      AfroTransact Retail
     * @param departments department node codes the organisation may sell in
     * @param requests    its department requests, newest first
     */
    record OrgScope(boolean exempt, Set<String> departments, List<DepartmentRequest> requests) {

        public OrgScope {
            departments = Set.copyOf(departments);
            requests = List.copyOf(requests);
        }
    }

    /** @param status PENDING, APPROVED or REJECTED; @param needs evidence still to provide (FOOD_PERMIT, …) */
    record DepartmentRequest(String departmentCode, String status, List<String> needs, Instant createdAt) {
    }

    /** seller-service did not answer. */
    class ScopeUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ScopeUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
