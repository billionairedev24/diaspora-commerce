package com.afrotransact.catalog.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.tax.MissingTaxFact;
import com.afrotransact.catalog.domain.tax.TaxDecision;
import com.afrotransact.catalog.domain.tax.TaxFactRules;
import com.afrotransact.catalog.domain.tax.TaxGate;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.TaxResolver;
import com.afrotransact.catalog.port.TaxResolver.Resolution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The tax publish gate: checks the answers to the category's tax questions of every variant (types, units, show-when
 * and required-when, the same rules as the seller form), asks tax-returns what they resolve to and returns each
 * variant's tax state. It never guesses: an unanswered or unresolved variant is never READY.
 */
@Component
class TaxGateEvaluator {

    private final TaxResolver resolver;
    private final boolean reviewRequired;

    TaxGateEvaluator(TaxResolver resolver, @Value("${at.catalog.tax.review-required:true}") boolean reviewRequired) {
        this.resolver = resolver;
        this.reviewRequired = reviewRequired;
    }

    /**
     * The outcome of an evaluation.
     *
     * @param variants  the tax state of each variant, in order
     * @param behaviour the seller's behaviour answers that apply to at least one variant (typed, hidden ones dropped)
     * @param errors    what refuses a submission: wrong answers, missing answers (not evidence files, which may follow
     *                  the upload of the file) and a closed category, keyed like the form's fields
     */
    record Evaluation(List<VariantTax> variants, Map<String, Object> behaviour, Map<String, String> errors) {

        boolean allReady() {
            return variants.stream().allMatch(VariantTax::ready);
        }
    }

    /**
     * @param category  the category node, null while the product waits on a category request
     * @param facts     the submitted product facts of each variant (owner VARIANT)
     * @param previous  the stored tax state of the variant each row matches, or null for a new variant: evidence file
     *                  references are kept from it
     * @param behaviour the seller's behaviour answers (owner OFFER)
     */
    Evaluation evaluate(ProductType type, CategoryNode category, List<Map<String, Object>> facts,
                        List<VariantTax> previous, Map<String, Object> behaviour) {
        List<AttributeDefinition> defs = type.taxFacts();
        Set<String> evidenceKeys = new HashSet<>();
        defs.stream().filter(d -> d.kind() == AttributeKind.FILE).forEach(d -> evidenceKeys.add(d.key()));
        String gate = category == null ? null : category.taxStatus();
        String profile = category == null ? null : category.taxProfileId();
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> behaviourUnion = new LinkedHashMap<>();
        List<VariantTax> out = new ArrayList<>();
        if (TaxGate.closed(gate)) {
            errors.put("category", TaxGate.message(gate));
        }
        for (int i = 0; i < facts.size(); i++) {
            String at = "variants[" + i + "].taxFacts.";
            Map<String, Object> merged = new HashMap<>();
            Map<String, Object> given = facts.get(i);
            for (AttributeDefinition d : defs) {
                if (TaxFactRules.OWNER_VARIANT.equals(d.owner()) && given.get(d.key()) != null) {
                    merged.put(d.key(), given.get(d.key()));
                } else if (TaxFactRules.OWNER_OFFER.equals(d.owner()) && behaviour != null && behaviour.get(d.key()) != null) {
                    merged.put(d.key(), behaviour.get(d.key()));
                }
            }
            VariantTax before = previous.get(i);
            for (String key : evidenceKeys) {
                merged.remove(key);
                if (before != null && before.facts().get(key) != null) {
                    merged.put(key, before.facts().get(key));
                }
            }
            TaxFactRules.Result r = TaxFactRules.check(defs, merged);
            List<Map<String, Object>> split = splitFacts(defs, r.facts());
            behaviourUnion.putAll(split.get(1));
            boolean answersMissing = false;
            for (Map.Entry<String, String> p : r.problems().entrySet()) {
                errors.put(fieldOf(defs, p.getKey(), at), p.getValue());
                answersMissing = true;
            }
            for (MissingTaxFact m : r.missing()) {
                if (!evidenceKeys.contains(m.factId())) {
                    errors.put(fieldOf(defs, m.factId(), at), m.message());
                    answersMissing = true;
                }
            }
            if (answersMissing || !r.missing().isEmpty()) {
                out.add(new VariantTax(split.get(0), TaxStatus.MISSING_FACTS, r.missing(), null));
                continue;
            }
            out.add(stable(before, gateOrResolve(type, profile, gate, split.get(0), split.get(1), r.missing())));
        }
        return new Evaluation(List.copyOf(out), behaviourUnion, errors);
    }

    /**
     * A variant the tax review already approved stays approved while its facts and its decision are unchanged: editing
     * a price must not send a live item back to review. Any change of answer or outcome starts the review again.
     */
    private static VariantTax stable(VariantTax before, VariantTax now) {
        if (before == null || !before.ready() || now.status() != TaxStatus.PENDING_REVIEW || before.decision() == null
                || now.decision() == null
                || !Objects.equals(before.decision().internalTaxCode(), now.decision().internalTaxCode())
                || !sameFacts(before.facts(), now.facts())) {
            return now;
        }
        return now.withStatus(TaxStatus.READY);
    }

    private static boolean sameFacts(Map<String, Object> a, Map<String, Object> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        return a.keySet().stream().allMatch(k -> canonical(a.get(k)).equals(canonical(b.get(k))));
    }

    private static String canonical(Object v) {
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    private VariantTax gateOrResolve(ProductType type, String profile, String gate, Map<String, Object> variantFacts,
                                     Map<String, Object> behaviourFacts, List<MissingTaxFact> none) {
        if (profile == null) {
            return new VariantTax(variantFacts, TaxStatus.NEEDS_APPROVAL, List.of(), null);
        }
        if (TaxGate.closed(gate)) {
            TaxStatus status = "BLOCKED_BUNDLE".equals(gate) ? TaxStatus.BLOCKED : TaxStatus.parse(gate);
            return new VariantTax(variantFacts, status, List.of(), null);
        }
        Resolution res = resolver.resolve(new TaxResolver.Request(profile, type.code(), variantFacts, behaviourFacts));
        return fromResolution(variantFacts, res);
    }

    private VariantTax fromResolution(Map<String, Object> facts, Resolution res) {
        TaxDecision decision = res.outcome() == null ? null : new TaxDecision(res.outcome(), res.internalTaxCode(),
                res.rowId(), res.rateDisplay(), res.ruleVersion(), res.reason());
        TaxStatus status = switch (res.status()) {
            case "RESOLVED" -> reviewRequired ? TaxStatus.PENDING_REVIEW : TaxStatus.READY;
            case "BLOCKED" -> TaxStatus.BLOCKED;
            case "NEEDS_APPROVAL" -> TaxStatus.NEEDS_APPROVAL;
            case "REFINE_CODE" -> TaxStatus.REFINE_CODE;
            case "CONFLICT_OPEN" -> TaxStatus.CONFLICT_OPEN;
            default -> TaxStatus.MISSING_FACTS;
        };
        return new VariantTax(facts, status, res.missingFacts(), decision);
    }

    private static String fieldOf(List<AttributeDefinition> defs, String factId, String variantPrefix) {
        boolean offer = defs.stream().anyMatch(d -> d.key().equals(factId) && TaxFactRules.OWNER_OFFER.equals(d.owner()));
        return (offer ? "sellerBehaviour." : variantPrefix) + factId;
    }

    private static List<Map<String, Object>> splitFacts(List<AttributeDefinition> defs, Map<String, Object> facts) {
        return TaxFactRules.split(defs, facts);
    }
}
