package com.afrotransact.seller.domain;

import java.util.List;
import java.util.Set;

/**
 * Which catalog departments an organisation may sell in.
 *
 * @param exempt      AfroTransact Retail: unrestricted
 * @param departments catalog department node codes; empty means nothing that belongs to a department can be listed yet
 * @param requests    department requests of the organisation, newest first
 */
public record CatalogScope(String orgId, boolean exempt, Set<String> departments, List<DepartmentRequest> requests) {

    public CatalogScope {
        departments = departments == null ? Set.of() : Set.copyOf(departments);
        requests = requests == null ? List.of() : List.copyOf(requests);
    }
}
