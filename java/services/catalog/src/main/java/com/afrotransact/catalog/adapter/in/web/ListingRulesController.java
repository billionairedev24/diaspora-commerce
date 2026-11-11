package com.afrotransact.catalog.adapter.in.web;

import com.afrotransact.catalog.adapter.in.web.ListingRulesDtos.RulesResponse;
import com.afrotransact.catalog.application.ListingRulesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Listing rules", description = "What a listing must say, per category: one source for the product form, "
        + "the API validation and the bulk upload template")
@RestController
class ListingRulesController {

    private final ListingRulesService rules;

    ListingRulesController(ListingRulesService rules) {
        this.rules = rules;
    }

    @Operation(operationId = "getListingRules", summary = "The listing rules of a category",
            description = "Kind of product, approval level and the status of every field (required, "
                    + "required_or_exemption, document, optional, hidden) for the category code (the category above "
                    + "the product type, such as dairy-eggs). 404 for a code the rules do not know.")
    @GetMapping("/v1/listing-rules")
    RulesResponse forCategory(@RequestParam String categoryCode) {
        return RulesResponse.of(rules.forCode(categoryCode));
    }
}
