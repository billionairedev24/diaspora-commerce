package com.afrotransact.catalog.domain.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ListingDocument;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.brand.BrandKeys;

/**
 * Applies "hidden" the same way for the form, the API and the bulk upload: an answer to a question the category does
 * not ask is ignored (never stored), condition is fixed to New where it is hidden, and every physical variant starts
 * from the listing's package when it has none of its own.
 */
public final class ListingNormaliser {

    private ListingNormaliser() {
    }

    /** The submitted listing parts with the hidden answers removed. */
    public record Result(Map<String, String> attributes, List<ProductImage> images, List<VariantDraft> variants,
                         ListingDetails details) {
    }

    public static Result apply(CategoryRules rules, Map<String, String> attributes, List<ProductImage> images,
                               List<VariantDraft> variants, ListingDetails details) {
        Map<String, String> attrs = new LinkedHashMap<>(attributes);
        rules.hiddenAttributeKeys().forEach(attrs::remove);
        RuleField.SUPERSEDED_KEYS.forEach(attrs::remove);
        if (!rules.shown(RuleField.BRAND)) {
            attrs.remove(BrandKeys.NAME);
            attrs.remove(BrandKeys.ID);
            attrs.remove(BrandKeys.REASON);
        }
        // The label photo is kept last: the first picture stays the product's main image.
        List<ProductImage> kept = Stream.concat(images.stream().filter(i -> !i.isLabel()),
                images.stream().filter(i -> i.isLabel() && rules.shown(RuleField.LABEL_PHOTO))).toList();

        boolean physical = rules.physical();
        boolean barcode = rules.shown(RuleField.PRODUCT_ID);
        List<VariantDraft> vs = variants.stream().map(v -> {
            VariantDraft out = barcode ? v : v.withoutBarcode();
            if (!physical) {
                return out.withPackaging(null);
            }
            return out.packaging() == null && details.packaging() != null ? out.withPackaging(details.packaging()) : out;
        }).toList();

        ListingDetails d = details;
        if (!rules.shown(RuleField.CONDITION)) {
            d = d.withCondition(ItemCondition.NEW);
        }
        Boolean dangerous = rules.shown(RuleField.DANGEROUS_GOODS) ? d.dangerousGoods() : null;
        List<ListingDocument> docs = d.documents().stream()
                .filter(x -> rules.ruleById(x.kind()).map(r -> r.status().shown()).orElse(false)).toList();
        Map<String, String> reasons = new LinkedHashMap<>();
        d.exemptions().forEach((id, why) -> rules.ruleById(id).filter(r -> r.status().shown()).ifPresent(r -> {
            if (why != null && !why.isBlank()) {
                reasons.put(id, why.strip());
            }
        }));
        d = d.withRuleAnswers(dangerous, docs, reasons)
                .withoutShipFromAndSizing(!rules.shown(RuleField.SHIP_FROM), !rules.shown(RuleField.SIZES));
        d = d.withPackaging(physical && !vs.isEmpty() ? vs.getFirst().packaging() : physical ? d.packaging() : null);
        return new Result(attrs, kept, vs, d);
    }
}
