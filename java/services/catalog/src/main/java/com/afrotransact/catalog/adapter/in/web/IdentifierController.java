package com.afrotransact.catalog.adapter.in.web;

import com.afrotransact.catalog.application.IdentifierService;
import com.afrotransact.catalog.application.IdentifierService.GtinCheck;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Identifiers", description = "GTIN / ISBN checks for the product builder and GTIN lookups by service "
        + "clients")
@RestController
class IdentifierController {

    private final IdentifierService identifiers;

    IdentifierController(IdentifierService identifiers) {
        this.identifiers = identifiers;
    }

    @Operation(summary = "Validate a GTIN and find an existing product page that already uses it",
            description = "Needs catalog:read (service tokens such as offers-service, any principal type) or "
                    + "catalog:write (sellers in the product builder).")
    @GetMapping("/v1/identifiers/gtin/{code}")
    GtinCheck check(@PathVariable String code, @RequestParam(defaultValue = "false") boolean isbn) {
        return identifiers.check(code, isbn);
    }
}
