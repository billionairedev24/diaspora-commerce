package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the signed-in seller's organisation may list in, shaped for Seller Center: the departments (named, from the
 * tree), whether any of them sells sized goods (so the Size charts page makes sense) and the department requests the
 * organisation has made. AfroTransact Retail and staff see every active department.
 */
public interface SellerCatalogScopeService {

    ScopeView mine();

    /**
     * @param exempt     Retail or staff: every department is allowed
     * @param hasSizing  true when some allowed department has a product type with the sizing trait
     * @param requests   the organisation's department requests, newest first
     */
    record ScopeView(boolean exempt, List<DepartmentView> departments, boolean hasSizing,
                     List<RequestView> requests) {
    }

    record DepartmentView(UUID id, String code, String name, boolean hasSizing) {
    }

    /** @param status PENDING, APPROVED or REJECTED; @param needs evidence still to provide (FOOD_PERMIT, BEAUTY_LABELLING) */
    record RequestView(String departmentCode, String departmentName, String status, List<String> needs,
                       Instant createdAt) {
    }
}
