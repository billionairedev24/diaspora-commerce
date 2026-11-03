package com.afrotransact.seller.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.domain.ApplicationData;
import com.afrotransact.seller.domain.CatalogScope;
import com.afrotransact.seller.domain.Department;
import com.afrotransact.seller.domain.DepartmentRequest;
import com.afrotransact.seller.domain.DepartmentRequestStatus;

/**
 * Which catalog departments an organisation may sell in (see docs/engineering/seller-catalog-scope.md). The departments
 * are recorded when the application is approved from the departments chosen at onboarding. catalog-service reads the
 * scope as a service ({@link #SERVICE_READ}) and enforces it; the organisation's members and staff can read it too.
 * Members request another department; staff change the set and decide the requests.
 */
public interface CatalogScopeService {

    /** Service scope: read any organisation's departments (catalog-service). */
    String SERVICE_READ = "sellers:catalog-scope.read";

    /** Largest page of the staff queue. */
    int QUEUE_LIMIT = 200;

    /** The catalog's active departments with the evidence each asks for (what onboarding offers). */
    List<Department> departments();

    /** The organisation's scope; AfroTransact Retail is exempt. */
    CatalogScope forOrg(String orgId);

    /** Final approval: records the departments chosen at onboarding for the new organisation (no-op for none). */
    void recordFromApplication(String orgId, ApplicationData data);

    /** A member (owner, admin, catalog) asks to also sell in a department; idempotent while one is pending. */
    DepartmentRequest request(String orgId, String departmentCode, String note);

    /** Staff: replaces the organisation's departments, with the reason (10+ characters). */
    CatalogScope setDepartments(String orgId, Set<String> departments, String reason);

    /** Staff: approve (the department joins the set) or reject a request. */
    DepartmentRequest decide(UUID requestId, boolean approve, String note);

    /** Staff: requests waiting (or decided), oldest first. */
    List<DepartmentRequest> queue(DepartmentRequestStatus status);
}
