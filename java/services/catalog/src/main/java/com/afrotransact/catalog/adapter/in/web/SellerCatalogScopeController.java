package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.application.SellerCatalogScopeService;
import com.afrotransact.catalog.application.SellerCatalogScopeService.ScopeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller scope", description = "The departments the signed-in seller's organisation may list in")
@RestController
class SellerCatalogScopeController {

    private final SellerCatalogScopeService scope;

    SellerCatalogScopeController(SellerCatalogScopeService scope) {
        this.scope = scope;
    }

    @Operation(summary = "Departments my organisation may list in (every department), and whether any sells sized goods",
            description = "A seller may list in any department, so exempt is always true, every department is "
                    + "returned and there are no requests. hasSizing is true when a department has a product type with "
                    + "the sizing trait. Never calls seller-service.")
    @GetMapping("/v1/seller/catalog-scope")
    SellerScopeResponse sellerCatalogScope() {
        return SellerScopeResponse.of(scope.mine());
    }

    @Schema(name = "SellerCatalogScope")
    record SellerScopeResponse(boolean exempt, List<DepartmentResponse> departments, boolean hasSizing,
                               List<RequestResponse> requests) {

        static SellerScopeResponse of(ScopeView v) {
            return new SellerScopeResponse(v.exempt(), v.departments().stream()
                    .map(d -> new DepartmentResponse(d.id(), d.code(), d.name(), d.hasSizing())).toList(),
                    v.hasSizing(), v.requests().stream().map(r -> new RequestResponse(r.departmentCode(),
                            r.departmentName(), r.status(), r.needs(), r.createdAt())).toList());
        }
    }

    @Schema(name = "SellerScopeDepartment")
    record DepartmentResponse(UUID id, String code, String name, boolean hasSizing) {
    }

    @Schema(name = "SellerScopeRequest")
    record RequestResponse(String departmentCode, String departmentName, String status, List<String> needs,
                           Instant createdAt) {
    }
}
