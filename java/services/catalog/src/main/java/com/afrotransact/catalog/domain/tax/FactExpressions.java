package com.afrotransact.catalog.domain.tax;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates the show-when / required-when expressions of tax facts. The grammar and semantics are those of
 * {@code tools/taxonomy/evaluator.py} and of the seller form's {@code fact-expressions.ts}; all three are tested with
 * the same vectors ({@code docs/engineering/fact-expression-vectors.json}).
 *
 * <p>Tree: {@code {all:[..]}}, {@code {any:[..]}}, {@code {not:x}} or a leaf {@code {op, fact, value|values}} with op
 * isTrue, isFalse, eq, in, gt, gte, lt, lte. A null expression is always true. A missing (null) fact makes every
 * leaf false, so nothing is ever decided over an answer that has not been given.
 */
public final class FactExpressions {

    private FactExpressions() {
    }

    public static boolean evaluate(Map<String, Object> expr, Map<String, Object> facts) {
        if (expr == null || expr.isEmpty()) {
            return true;
        }
        if (expr.containsKey("all")) {
            return children(expr.get("all")).stream().allMatch(e -> evaluate(e, facts));
        }
        if (expr.containsKey("any")) {
            return children(expr.get("any")).stream().anyMatch(e -> evaluate(e, facts));
        }
        if (expr.containsKey("not")) {
            return !evaluate(asMap(expr.get("not")), facts);
        }
        String op = String.valueOf(expr.get("op"));
        Object v = facts.get(String.valueOf(expr.get("fact")));
        if ("isTrue".equals(op)) {
            return Boolean.TRUE.equals(v);
        }
        if ("isFalse".equals(op)) {
            return Boolean.FALSE.equals(v);
        }
        if (v == null) {
            return false;
        }
        switch (op) {
            case "eq":
                return same(v, expr.get("value"));
            case "in":
                return valuesContain(expr.get("values"), v);
            default:
                break;
        }
        BigDecimal n = number(v);
        BigDecimal ref = number(expr.get("value"));
        if (n == null) {
            return false;
        }
        int c = n.compareTo(ref == null ? BigDecimal.ZERO : ref);
        return switch (op) {
            case "gt" -> c > 0;
            case "gte" -> c >= 0;
            case "lt" -> c < 0;
            case "lte" -> c <= 0;
            default -> throw new IllegalArgumentException("Unknown operator " + op);
        };
    }

    /** The fact ids an expression refers to. */
    public static Set<String> factsOf(Map<String, Object> expr) {
        Set<String> out = new HashSet<>();
        collect(expr, out);
        return out;
    }

    private static void collect(Map<String, Object> expr, Set<String> out) {
        if (expr == null) {
            return;
        }
        for (String key : List.of("all", "any")) {
            children(expr.get(key)).forEach(e -> collect(e, out));
        }
        if (expr.get("not") != null) {
            collect(asMap(expr.get("not")), out);
        }
        if (expr.get("fact") != null) {
            out.add(String.valueOf(expr.get("fact")));
        }
    }

    private static boolean valuesContain(Object values, Object v) {
        if (values instanceof List<?> list) {
            return list.stream().anyMatch(x -> same(v, x));
        }
        return false;
    }

    private static boolean same(Object a, Object b) {
        BigDecimal na = number(a);
        BigDecimal nb = number(b);
        if (na != null && nb != null) {
            return na.compareTo(nb) == 0;
        }
        return a.equals(b);
    }

    private static BigDecimal number(Object v) {
        if (v instanceof Boolean || !(v instanceof Number)) {
            return null;
        }
        return new BigDecimal(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<Map<String, Object>> children(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().map(FactExpressions::asMap).toList();
        }
        return List.of();
    }
}
