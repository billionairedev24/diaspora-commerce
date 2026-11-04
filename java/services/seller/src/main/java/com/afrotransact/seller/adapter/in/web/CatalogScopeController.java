package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.application.CatalogScopeService;
import com.afrotransact.seller.domain.CatalogScope;
import com.afrotransact.seller.domain.Department;
import com.afrotransact.seller.domain.Department.Requirement;
import com.afrotransact.seller.domain.DepartmentRequest;
import com.afrotransact.seller.domain.DepartmentRequestStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Which catalog departments an organisation may sell in, and the requests to add one. */
@Tag(name = "Catalog scope", description = "The catalog departments an organisation may sell in. catalog-service reads "
        + "and enforces them; staff change them and decide the requests sellers make.")
@RestController
class CatalogScopeController {

    private final CatalogScopeService scopes;

    CatalogScopeController(CatalogScopeService scopes) {
        this.scopes = scopes;
    }

    @Operation(summary = "The departments an organisation may sell in",
            description = "Readable by the organisation's members, by staff, and by catalog-service "
                    + "(sellers:catalog-scope.read). departments are catalog department node codes, recorded when the "
                    + "application was approved; none means nothing in a department can be listed yet. exempt is "
                    + "AfroTransact Retail.")
    @GetMapping("/v1/orgs/{orgId}/catalog-scope")
    CatalogScopeResponse scope(@PathVariable String orgId) {
        return CatalogScopeResponse.of(scopes.forOrg(orgId));
    }

    @Operation(summary = "The catalog's departments with the evidence each asks for (what onboarding offers)",
            description = "Any signed-in user. code is the catalog department node code; requires lists FOOD_PERMIT "
                    + "and/or BEAUTY_LABELLING, taken from the department's compliance data in the catalog.")
    @GetMapping("/v1/departments")
    List<DepartmentResponse> departments() {
        return scopes.departments().stream().map(DepartmentResponse::of).toList();
    }

    @Operation(summary = "Ask to sell in another department (owner, admin, catalog)",
            description = "Staff approve or reject. Idempotent while one is pending. 409 "
                    + "seller.department-already-allowed when the department is already in the set. needs lists the "
                    + "evidence still to provide (food permit, beauty labelling).")
    @PostMapping("/v1/orgs/{orgId}/department-requests")
    @ResponseStatus(HttpStatus.CREATED)
    DepartmentRequestResponse request(@PathVariable String orgId, @Valid @RequestBody RequestDepartment body) {
        return DepartmentRequestResponse.of(scopes.request(orgId, body.departmentCode(), body.note()));
    }

    @Operation(summary = "Staff: replace an organisation's departments, with the reason (applications:approve)")
    @PutMapping("/v1/orgs/{orgId}/catalog-scope/departments")
    CatalogScopeResponse setDepartments(@PathVariable String orgId, @Valid @RequestBody SetDepartments body) {
        return CatalogScopeResponse.of(scopes.setDepartments(orgId, Set.copyOf(body.departments()), body.reason()));
    }

    @Operation(summary = "Staff: department requests waiting for a decision (applications:review)")
    @GetMapping("/v1/department-requests")
    List<DepartmentRequestResponse> queue(@RequestParam(required = false) DepartmentRequestStatus status) {
        return scopes.queue(status).stream().map(DepartmentRequestResponse::of).toList();
    }

    @Operation(summary = "Staff: one department request (applications:review)",
            description = "needs lists the evidence the organisation still has to provide; it is empty for a rejected "
                    + "request.")
    @GetMapping("/v1/department-requests/{id}")
    DepartmentRequestResponse departmentRequest(@PathVariable UUID id) {
        return DepartmentRequestResponse.of(scopes.request(id));
    }

    @Operation(summary = "Staff: approve or reject a department request (applications:approve)",
            description = "A rejection needs a note (3+ characters): the seller reads it. "
                    + "Approving adds the department to the organisation's set at once.")
    @PostMapping("/v1/department-requests/{id}/decision")
    DepartmentRequestResponse decide(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return DepartmentRequestResponse.of(scopes.decide(id, body.approve(), body.note()));
    }

    record RequestDepartment(@NotBlank String departmentCode, @Size(max = 500) String note) {
    }

    record SetDepartments(@NotNull List<@NotBlank String> departments, @NotBlank String reason) {
    }

    record Decision(@NotNull Boolean approve, @Size(max = 500) String note) {
    }

    @Schema(name = "DepartmentRequest")
    record DepartmentRequestResponse(UUID id, String orgId, String departmentCode, String note, String status,
                                     String requestedBy, Instant createdAt, String decidedBy, Instant decidedAt,
                                     String decisionNote, List<String> needs) {

        static DepartmentRequestResponse of(DepartmentRequest r) {
            return new DepartmentRequestResponse(r.id(), r.orgId(), r.departmentCode(), r.note(), r.status().name(),
                    r.requestedBy(), r.createdAt(), r.decidedBy(), r.decidedAt(), r.decisionNote(),
                    r.needs().stream().map(Requirement::name).sorted().toList());
        }
    }

    @Schema(name = "CatalogScope")
    record CatalogScopeResponse(String orgId, boolean exempt, List<String> departments,
                                List<DepartmentRequestResponse> requests) {

        static CatalogScopeResponse of(CatalogScope s) {
            return new CatalogScopeResponse(s.orgId(), s.exempt(), s.departments().stream().sorted().toList(),
                    s.requests().stream().map(DepartmentRequestResponse::of).toList());
        }
    }

    @Schema(name = "SellerDepartment")
    record DepartmentResponse(String code, String name, List<String> requires) {

        static DepartmentResponse of(Department d) {
            return new DepartmentResponse(d.code(), d.name(), d.requires().stream().map(Requirement::name).sorted()
                    .toList());
        }
    }
}
