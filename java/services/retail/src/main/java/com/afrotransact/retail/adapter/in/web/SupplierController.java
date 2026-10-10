package com.afrotransact.retail.adapter.in.web;

import java.util.UUID;

import com.afrotransact.retail.adapter.in.web.SupplierDtos.StatusRequest;
import com.afrotransact.retail.adapter.in.web.SupplierDtos.SupplierRequest;
import com.afrotransact.retail.adapter.in.web.SupplierDtos.SupplierResponse;
import com.afrotransact.retail.application.SupplierService;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Suppliers", description = "Supplier records, lifecycle and receipt-driven scorecards (S069)")
@RestController
@RequestMapping("/v1/suppliers")
class SupplierController {

    private final SupplierService suppliers;

    SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @Operation(operationId = "createSupplier", summary = "Create a supplier (retail:suppliers:write); starts in ONBOARDING")
    @PostMapping
    ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest body) {
        Supplier s = suppliers.create(body.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).eTag("\"" + s.version() + "\"")
                .body(SupplierResponse.of(s));
    }

    @Operation(operationId = "listSuppliers", summary = "List suppliers (retail:read)")
    @GetMapping
    PageResponse<SupplierResponse> list(@RequestParam(required = false) SupplierStatus status,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(suppliers.list(status, q, page, size), SupplierResponse::of);
    }

    @Operation(operationId = "getSupplier", summary = "A supplier with its scorecard (retail:read)")
    @GetMapping("/{id}")
    ResponseEntity<SupplierResponse> get(@PathVariable UUID id) {
        Supplier s = suppliers.get(id);
        return Versions.tagged(SupplierResponse.of(s), s.version());
    }

    @Operation(operationId = "updateSupplier", summary = "Update supplier master data (retail:suppliers:write; If-Match)")
    @PutMapping("/{id}")
    ResponseEntity<SupplierResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierRequest body,
                                            @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        Supplier s = suppliers.update(id, body.toCommand(), Versions.expected(ifMatch));
        return Versions.tagged(SupplierResponse.of(s), s.version());
    }

    @Operation(operationId = "changeSupplierStatus", summary = "Change supplier status: ONBOARDING → APPROVED ↔ ON_WATCH → INACTIVE → APPROVED",
            description = "Inactive and onboarding suppliers cannot receive new POs. ON_WATCH and INACTIVE need a reason.")
    @PostMapping("/{id}/status")
    ResponseEntity<SupplierResponse> status(@PathVariable UUID id, @Valid @RequestBody StatusRequest body,
                                            @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        Supplier s = suppliers.changeStatus(id, body.status(), body.reason(), Versions.expected(ifMatch));
        return Versions.tagged(SupplierResponse.of(s), s.version());
    }
}
