package com.afrotransact.catalog.domain.taxonomy.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The category tax dataset ({@code tools/taxonomy/category_tax_map.json}) read for the catalog: departments,
 * categories with their tax status and the questions (facts) each one asks. Only what the catalog needs; the decision
 * tables stay with tax-returns. Built from the parsed JSON (maps and lists), so the domain needs no JSON library.
 */
public record TaxonomyDataset(String ruleVersion, List<Department> departments, List<Category> categories,
                              Map<String, Fact> facts) {

    public record Department(String code, String name, int position) {
    }

    public record Choice(String value, String label) {
    }

    /** A fact definition. */
    public record Fact(String id, String label, String kind, String unit, String owner, String help, boolean evidence,
                       List<Choice> choices) {
    }

    /** A category's use of a fact: always required, or required when the expression holds; choices narrowed. */
    public record FactRef(String fact, boolean required, Map<String, Object> requiredWhen, List<String> choices) {
    }

    public record ProductTypeDef(String code, String label, boolean sized, boolean isDefault) {
    }

    public record Category(String code, String department, String name, int position, String status,
                           List<String> statusReasons, List<FactRef> facts, List<ProductTypeDef> productTypes) {
    }

    public TaxonomyDataset {
        departments = List.copyOf(departments);
        categories = List.copyOf(categories);
        facts = Map.copyOf(facts);
    }

    @SuppressWarnings("unchecked")
    public static TaxonomyDataset of(Map<String, Object> root) {
        List<Department> departments = new ArrayList<>();
        for (Object o : list(root.get("departments"))) {
            Map<String, Object> d = (Map<String, Object>) o;
            departments.add(new Department(str(d.get("code")), str(d.get("name")), integer(d.get("position"))));
        }
        Map<String, Fact> facts = new LinkedHashMap<>();
        for (Object o : list(root.get("facts"))) {
            Map<String, Object> f = (Map<String, Object>) o;
            List<Choice> choices = new ArrayList<>();
            for (Object c : list(f.get("choices"))) {
                Map<String, Object> cm = (Map<String, Object>) c;
                choices.add(new Choice(str(cm.get("value")), str(cm.get("label"))));
            }
            facts.put(str(f.get("id")), new Fact(str(f.get("id")), str(f.get("label")), str(f.get("kind")),
                    f.get("unit") == null ? null : str(f.get("unit")), str(f.get("owner")),
                    f.get("help") == null ? null : str(f.get("help")), Boolean.TRUE.equals(f.get("evidence")), choices));
        }
        List<Category> categories = new ArrayList<>();
        for (Object o : list(root.get("categories"))) {
            Map<String, Object> c = (Map<String, Object>) o;
            List<FactRef> refs = new ArrayList<>();
            for (Object r : list(c.get("facts"))) {
                Map<String, Object> rm = (Map<String, Object>) r;
                List<String> narrowed = new ArrayList<>();
                list(rm.get("choices")).forEach(x -> narrowed.add(str(x)));
                refs.add(new FactRef(str(rm.get("fact")), !Boolean.FALSE.equals(rm.get("required")),
                        (Map<String, Object>) rm.get("requiredWhen"), narrowed));
            }
            List<ProductTypeDef> types = new ArrayList<>();
            for (Object t : list(c.get("productTypes"))) {
                Map<String, Object> tm = (Map<String, Object>) t;
                types.add(new ProductTypeDef(str(tm.get("code")), str(tm.get("label")),
                        Boolean.TRUE.equals(tm.get("sized")), Boolean.TRUE.equals(tm.get("default"))));
            }
            List<String> reasons = new ArrayList<>();
            for (Object r : list(c.get("statusReasons"))) {
                reasons.add(r instanceof Map<?, ?> m ? str(m.get("text")) : str(r));
            }
            categories.add(new Category(str(c.get("code")), str(c.get("department")), str(c.get("name")),
                    integer(c.get("position")), str(c.get("status")), reasons, refs, types));
        }
        return new TaxonomyDataset(str(root.get("ruleVersion")), departments, categories, facts);
    }

    public Fact fact(String id) {
        return Objects.requireNonNull(facts.get(id), "Unknown fact " + id);
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int integer(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
