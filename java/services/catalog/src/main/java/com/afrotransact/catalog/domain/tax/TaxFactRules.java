package com.afrotransact.catalog.domain.tax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;

/**
 * Validation of the tax facts of a variant and its seller's listing against a product type's fact questions. The
 * same rules run in the seller form (fact-rules.ts), so the seller sees the server's answer early.
 *
 * <p>Facts that the show-when expression hides are dropped (a stale answer never reaches the decision). A fact is
 * missing when it is shown, required and has no value. Evidence (FILE) facts are held by the server once a file is
 * uploaded and are never taken from a request.
 */
public final class TaxFactRules {

    public static final String OWNER_VARIANT = "VARIANT";
    public static final String OWNER_OFFER = "OFFER";

    private static final BigDecimal MAX_NUMBER = new BigDecimal("1000000");

    private TaxFactRules() {
    }

    /**
     * The result of checking one set of answers.
     *
     * @param facts    the visible answers, typed (BOOLEAN true/false, DECIMAL and PERCENT numbers, INTEGER whole numbers,
     *                 CHOICE the option value, FILE the evidence reference)
     * @param problems fact id to what is wrong with the answer given
     * @param missing  shown, required facts without an answer, in form order
     */
    public record Result(Map<String, Object> facts, Map<String, String> problems, List<MissingTaxFact> missing) {

        public boolean valid() {
            return problems.isEmpty() && missing.isEmpty();
        }

        /** The missing facts that are not evidence files (those may follow the submission). */
        public List<MissingTaxFact> missingAnswers(Set<String> evidenceIds) {
            return missing.stream().filter(m -> !evidenceIds.contains(m.factId())).toList();
        }
    }

    /** The plain message for a missing fact: "Net weight (oz) is needed. The net weight of one package..." */
    public static String missingMessage(AttributeDefinition def) {
        String what = def.kind() == AttributeKind.FILE ? "Please upload: " + def.label() + "."
                : def.label() + " is needed.";
        return def.help() == null ? what : what + " " + def.help();
    }

    /**
     * Checks answers against the fact questions.
     *
     * @param defs the type's tax facts (variant and listing facts together: a question may depend on either)
     * @param raw  answers by fact id, JSON-typed (strings of numbers and "yes"/"no" are accepted)
     */
    public static Result check(List<AttributeDefinition> defs, Map<String, Object> raw) {
        Map<String, Object> facts = new LinkedHashMap<>();
        Map<String, String> problems = new LinkedHashMap<>();
        for (AttributeDefinition def : defs) {
            Object given = raw == null ? null : raw.get(def.key());
            if (given == null || given instanceof String s && s.isBlank()) {
                continue;
            }
            Object value = coerce(def, given, problems);
            if (value != null) {
                facts.put(def.key(), value);
            }
        }
        hide(defs, facts, problems);
        List<MissingTaxFact> missing = new ArrayList<>();
        for (AttributeDefinition def : defs) {
            if (def.required() && !facts.containsKey(def.key()) && !problems.containsKey(def.key())
                    && FactExpressions.evaluate(def.requiredWhen(), facts)) {
                missing.add(new MissingTaxFact(def.key(), def.label(), missingMessage(def)));
            }
        }
        return new Result(facts, problems, missing);
    }

    /** Drops the answers of questions that are not asked, until no answer depends on a dropped one. */
    private static void hide(List<AttributeDefinition> defs, Map<String, Object> facts, Map<String, String> problems) {
        boolean changed = true;
        for (int round = 0; changed && round <= defs.size(); round++) {
            changed = false;
            for (AttributeDefinition def : defs) {
                if (!FactExpressions.evaluate(def.showWhen(), facts)
                        && (facts.remove(def.key()) != null | problems.remove(def.key()) != null)) {
                    changed = true;
                }
            }
        }
    }

    private static Object coerce(AttributeDefinition def, Object given, Map<String, String> problems) {
        String key = def.key();
        switch (def.kind()) {
            case BOOLEAN: {
                if (given instanceof Boolean b) {
                    return b;
                }
                String t = String.valueOf(given).trim().toLowerCase(Locale.ROOT);
                if (Set.of("true", "yes", "y", "1").contains(t)) {
                    return Boolean.TRUE;
                }
                if (Set.of("false", "no", "n", "0").contains(t)) {
                    return Boolean.FALSE;
                }
                problems.put(key, def.label() + ": answer yes or no");
                return null;
            }
            case DECIMAL, INTEGER, PERCENT: {
                BigDecimal n = number(given);
                if (n == null) {
                    problems.put(key, def.label() + ": enter a number" + unitHint(def));
                    return null;
                }
                if (def.kind() == AttributeKind.PERCENT) {
                    if (n.signum() < 0 || n.compareTo(BigDecimal.valueOf(100)) > 0) {
                        problems.put(key, def.label() + ": enter a percentage from 0 to 100");
                        return null;
                    }
                    return n.stripTrailingZeros();
                }
                if (n.signum() <= 0 || n.compareTo(MAX_NUMBER) > 0) {
                    problems.put(key, def.label() + ": enter a number above 0" + unitHint(def));
                    return null;
                }
                if (def.kind() == AttributeKind.INTEGER) {
                    if (n.stripTrailingZeros().scale() > 0) {
                        problems.put(key, def.label() + ": enter a whole number");
                        return null;
                    }
                    return n.longValueExact();
                }
                return n.stripTrailingZeros();
            }
            case ENUM: {
                String v = String.valueOf(given).trim();
                if (def.options().contains(v)) {
                    return v;
                }
                problems.put(key, def.label() + ": choose one of the listed answers");
                return null;
            }
            case FILE:
                return given instanceof String s && !s.isBlank() ? s : null;
            default:
                problems.put(key, def.label() + ": not a tax question");
                return null;
        }
    }

    private static String unitHint(AttributeDefinition def) {
        return def.unit() == null ? "" : " (in " + def.unit() + ")";
    }

    private static BigDecimal number(Object given) {
        if (given instanceof Boolean) {
            return null;
        }
        if (given instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(given).trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** The definitions of one owner (VARIANT or OFFER). */
    public static List<AttributeDefinition> ownedBy(List<AttributeDefinition> defs, String owner) {
        return defs.stream().filter(d -> owner.equals(d.owner())).toList();
    }

    /** Splits typed facts by owner: the first map holds the variant's facts, the second the listing's behaviour. */
    public static List<Map<String, Object>> split(List<AttributeDefinition> defs, Map<String, Object> facts) {
        Map<String, Object> variant = new LinkedHashMap<>();
        Map<String, Object> behaviour = new LinkedHashMap<>();
        for (AttributeDefinition def : defs) {
            Object v = facts.get(def.key());
            if (v != null) {
                (OWNER_OFFER.equals(def.owner()) ? behaviour : variant).put(def.key(), v);
            }
        }
        return List.of(variant, behaviour);
    }
}
