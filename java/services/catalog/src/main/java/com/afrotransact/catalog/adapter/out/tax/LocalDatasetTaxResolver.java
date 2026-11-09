package com.afrotransact.catalog.adapter.out.tax;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.tax.FactExpressions;
import com.afrotransact.catalog.domain.tax.MissingTaxFact;
import com.afrotransact.catalog.port.TaxResolver;
import com.afrotransact.catalog.port.TaxonomySource;

/**
 * The local fake of tax-returns' resolve endpoint, built from the category tax dataset: the same decision rows,
 * required-fact checks and gate precedence as the reference evaluator ({@code tools/taxonomy/evaluator.py}). It is
 * what runs when no tax-returns is configured (local profile and tests); production uses {@link HttpTaxResolver}.
 */
public class LocalDatasetTaxResolver implements TaxResolver {

    private final String ruleVersion;
    private final Map<String, Map<String, Object>> categories = new HashMap<>();
    private final Map<String, Map<String, Object>> facts = new HashMap<>();

    @SuppressWarnings("unchecked")
    public LocalDatasetTaxResolver(TaxonomySource source) {
        Map<String, Object> doc = source.bundledDocument();
        this.ruleVersion = String.valueOf(doc.get("ruleVersion"));
        for (Object c : (List<Object>) doc.get("categories")) {
            Map<String, Object> cm = (Map<String, Object>) c;
            categories.put(String.valueOf(cm.get("code")), cm);
        }
        for (Object f : (List<Object>) doc.get("facts")) {
            Map<String, Object> fm = (Map<String, Object>) f;
            facts.put(String.valueOf(fm.get("id")), fm);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Resolution resolve(Request request) {
        Map<String, Object> category = categories.get(request.categoryCode());
        if (category == null) {
            throw new TaxProfileNotFound(request.categoryCode());
        }
        Map<String, Object> values = new HashMap<>();
        putAll(values, request.facts());
        putAll(values, request.sellerBehaviour());
        values.putIfAbsent("exemption_window_active", Boolean.FALSE);
        List<Map<String, Object>> refs = (List<Map<String, Object>>) category.get("facts");
        List<MissingTaxFact> missing = new ArrayList<>();
        for (Map<String, Object> ref : refs) {
            String id = String.valueOf(ref.get("fact"));
            Map<String, Object> def = facts.get(id);
            Map<String, Object> when = (Map<String, Object>) ref.get("requiredWhen");
            if (!Boolean.TRUE.equals(ref.get("required")) || !FactExpressions.evaluate(when, values)) {
                continue;
            }
            Object v = values.get(id);
            if (v == null || "".equals(v)) {
                String label = String.valueOf(def.get("label"));
                missing.add(new MissingTaxFact(id, label, label + " is needed. " + def.get("help")));
            }
        }
        if (!missing.isEmpty()) {
            return new Resolution("MISSING_FACTS", null, null, null, null, missing, ruleVersion,
                    "Facts are missing; the tax is never guessed.");
        }
        Map<String, Object> relevant = new HashMap<>();
        for (Map<String, Object> ref : refs) {
            String id = String.valueOf(ref.get("fact"));
            if (Boolean.TRUE.equals(ref.get("required"))
                    && FactExpressions.evaluate((Map<String, Object>) ref.get("requiredWhen"), values)
                    && values.get(id) != null) {
                relevant.put(id, values.get(id));
            }
        }
        for (Map<String, Object> row : (List<Map<String, Object>>) category.get("decisionTable")) {
            if (FactExpressions.evaluate((Map<String, Object>) row.get("when"), relevant)) {
                return decision(category, row);
            }
        }
        return new Resolution("MISSING_FACTS", null, null, null, null, List.of(), ruleVersion,
                "No rule matches these facts; escalate to tax review.");
    }

    private Resolution decision(Map<String, Object> category, Map<String, Object> row) {
        String gate = String.valueOf(category.get("status"));
        String outcome = String.valueOf(row.get("outcome"));
        String status = "BLOCKED".equals(outcome) || "BLOCKED_BUNDLE".equals(gate) ? "BLOCKED"
                : List.of("CONFLICT_OPEN", "NEEDS_APPROVAL", "REFINE_CODE").contains(gate) ? gate : "RESOLVED";
        return new Resolution(status, outcome, String.valueOf(row.get("internalTaxCode")), String.valueOf(row.get("id")),
                String.valueOf(row.get("rateDisplay")), List.of(), ruleVersion, String.valueOf(row.get("condition")));
    }

    private static void putAll(Map<String, Object> target, Map<String, Object> source) {
        if (source != null) {
            source.forEach((k, v) -> {
                if (v != null) {
                    target.put(k, v);
                }
            });
        }
    }
}
