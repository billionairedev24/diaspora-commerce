package com.afrotransact.catalog.adapter.in.web;

import com.afrotransact.catalog.application.GtinPrefillService;
import com.afrotransact.catalog.application.GtinPrefillService.GtinPrefill;
import com.afrotransact.catalog.application.IdentifierService;
import com.afrotransact.catalog.application.IdentifierService.GtinCheck;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Identifiers", description = "GTIN / ISBN checks and GTIN prefill for the product builder, GTIN lookups "
        + "by service clients")
@RestController
class IdentifierController {

    private final IdentifierService identifiers;
    private final GtinPrefillService prefill;

    IdentifierController(IdentifierService identifiers, GtinPrefillService prefill) {
        this.identifiers = identifiers;
        this.prefill = prefill;
    }

    @Operation(summary = "Validate a GTIN and find an existing product page that already uses it",
            description = "Needs catalog:read (service tokens such as offers-service, any principal type) or "
                    + "catalog:write (sellers in the product builder).")
    @GetMapping("/v1/identifiers/gtin/{code}")
    GtinCheck check(@PathVariable String code, @RequestParam(defaultValue = "false") boolean isbn) {
        return identifiers.check(code, isbn);
    }

    @Operation(summary = "Prefill the product builder from a GTIN (GS1, Open Food Facts)",
            description = "Validates the GTIN-8/12/13/14 check digit (422 with a suggested fix), then asks the "
                    + "product-data providers in order (GS1 Verified when configured, then Open Food Facts for "
                    + "groceries) and returns title, brand, net content, images and category hints in our taxonomy. "
                    + "`importedFrom` marks imported fields (they stay editable); `found=false` means manual entry. "
                    + "When a product page already uses the GTIN, `existingAtin` is set and no provider is called. "
                    + "Answers are cached per GTIN. Needs catalog:write or catalog:read.")
    @GetMapping("/v1/identifiers/gtin/{code}/prefill")
    GtinPrefill prefillGtin(@PathVariable String code) {
        return prefill.prefill(code);
    }
}
