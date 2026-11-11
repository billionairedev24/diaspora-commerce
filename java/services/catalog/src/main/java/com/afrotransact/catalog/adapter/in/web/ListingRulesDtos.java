package com.afrotransact.catalog.adapter.in.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.domain.rules.FieldRule;
import com.afrotransact.catalog.domain.rules.RuleAwareType;
import io.swagger.v3.oas.annotations.media.Schema;

/** Response of GET /v1/listing-rules: what the product form, the API and the upload template ask for one category. */
final class ListingRulesDtos {

    private ListingRulesDtos() {
    }

    @Schema(name = "ListingRuleField", description = "One field of the matrix for this category. status is one of "
            + "required, required_or_exemption, document, optional, hidden. A hidden field is not shown and a submitted "
            + "value is ignored. A required_or_exemption field may be left empty when listing.exemptions gives a reason.")
    record FieldResponse(String id, String group, String label, String status, String note, String control,
                         String attributeKey, List<String> options, List<String> units, boolean perVariant) {

        static FieldResponse of(FieldRule r) {
            return new FieldResponse(r.field().id(), r.group(), r.field().plainLabel(r.label()), r.status().code(),
                    r.note(), r.field().control().name().toLowerCase(Locale.ROOT), r.field().attributeKey(),
                    r.field().options(), r.field().units(), r.field().perVariant());
        }
    }

    @Schema(name = "ListingRules", description = "The listing rules of one category, from the single rules source. "
            + "replacedAttributeKeys are the per-product-type detail keys the rules own: the form shows the rule field "
            + "instead and ignores those product-type attributes.")
    record RulesResponse(String categoryCode, String categoryName, String department, String kind, String kindName,
                         String approval, String note, boolean provisional, List<FieldResponse> fields,
                         List<String> replacedAttributeKeys) {

        static RulesResponse of(CategoryRules c) {
            return new RulesResponse(c.code(), c.name(), c.department(), c.kindCode(), c.kindName(), c.approval(),
                    c.note(), c.provisional(), c.fields().stream().map(FieldResponse::of).toList(),
                    RuleAwareType.governedKeys().stream().sorted().toList());
        }
    }

    @Schema(name = "ListingKinds")
    record KindsResponse(Map<String, String> kinds) {
    }
}
