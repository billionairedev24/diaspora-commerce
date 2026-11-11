package com.afrotransact.seller.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.application.SellingViews.RuleView;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.SellingRuleRepository.RuleAudit;

/**
 * Staff manage the selling rules: scope rules (category, product type, brand, brand in a category, product,
 * capability), the gated brands and the documents each asks for. Viewing needs any applications permission or
 * {@code selling-rules:manage}; changing needs {@code selling-rules:manage}, a reason (10+ characters), and is audited.
 */
public interface SellingRuleAdminService {

    Page<RuleView> search(String scopeType, String level, String text, boolean activeOnly, int page, int size);

    RuleView get(UUID id);

    List<RuleAudit> audit(UUID id);

    /** A new rule (typically a gated brand). 409 when the scope already has one. */
    RuleView create(RuleInput input, String reason, String actorName);

    /** Edits a rule; the scope never changes. {@code input.version()} is the version the editor read. */
    RuleView update(UUID id, RuleInput input, String reason, String actorName);

    /**
     * @param key          category or product type code, brand name, barcode, or capability code (normalised here)
     * @param categoryCode with {@code BRAND_IN_CATEGORY}: the category the brand is gated in
     * @param documents    document kinds with whether they are optional ("or tell us why not")
     * @param materials    jewelry: the materials that make a conditional rule apply (gold, silver, gemstone)
     * @param watch        conditional rule: also applies to watches
     * @param reason       seller-visible text for NOT_AVAILABLE
     */
    record RuleInput(String scopeType, String key, String categoryCode, String label, String level,
                     List<Doc> documents, String reason, String note, String capability, List<String> materials,
                     boolean watch, Integer reapplyAfterDays, Boolean active, Long version) {

        public RuleInput {
            documents = documents == null ? List.of() : List.copyOf(documents);
            materials = materials == null ? List.of() : List.copyOf(materials);
        }
    }

    record Doc(String kind, boolean optional) {
    }
}
