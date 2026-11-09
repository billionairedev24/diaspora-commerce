package com.afrotransact.taxreturns.domain.profile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A condition over a flat map of facts: {@code {all:[]}}, {@code {any:[]}}, {@code {not:x}} or a leaf
 * {@code {op: isTrue|isFalse|eq|in|gt|gte|lt|lte, fact, value|values}}. A missing (null) fact makes every leaf false, so
 * a decision is never reached by guessing over a missing answer (the caller checks required facts first). Numbers
 * compare as decimals; booleans are never numbers.
 */
public sealed interface Condition {

    boolean holds(Map<String, Object> facts);

    /** Fact ids this condition looks at. */
    Set<String> factIds();

    /** The expression tree as plain maps and lists, as stored and served. */
    Map<String, Object> toTree();

    /** All conditions must hold (an empty list holds). */
    record All(List<Condition> parts) implements Condition {

        public All {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean holds(Map<String, Object> facts) {
            return parts.stream().allMatch(p -> p.holds(facts));
        }

        @Override
        public Set<String> factIds() {
            return union(parts);
        }

        @Override
        public Map<String, Object> toTree() {
            return Map.of("all", parts.stream().map(Condition::toTree).toList());
        }
    }

    /** At least one condition holds (an empty list does not). */
    record Any(List<Condition> parts) implements Condition {

        public Any {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean holds(Map<String, Object> facts) {
            return parts.stream().anyMatch(p -> p.holds(facts));
        }

        @Override
        public Set<String> factIds() {
            return union(parts);
        }

        @Override
        public Map<String, Object> toTree() {
            return Map.of("any", parts.stream().map(Condition::toTree).toList());
        }
    }

    /** Negation. */
    record Not(Condition inner) implements Condition {

        @Override
        public boolean holds(Map<String, Object> facts) {
            return !inner.holds(facts);
        }

        @Override
        public Set<String> factIds() {
            return inner.factIds();
        }

        @Override
        public Map<String, Object> toTree() {
            return Map.of("not", inner.toTree());
        }
    }

    /** Comparison operators of a leaf. */
    enum Op {
        isTrue, isFalse, eq, in, gt, gte, lt, lte
    }

    /**
     * One comparison.
     *
     * @param op     operator
     * @param fact   fact id
     * @param value  comparison value (eq, gt, gte, lt, lte)
     * @param values allowed values (in)
     */
    record Leaf(Op op, String fact, Object value, List<Object> values) implements Condition {

        public Leaf {
            values = values == null ? List.of() : List.copyOf(values);
        }

        @Override
        public boolean holds(Map<String, Object> facts) {
            Object v = facts.get(fact);
            switch (op) {
                case isTrue:
                    return Boolean.TRUE.equals(v);
                case isFalse:
                    return Boolean.FALSE.equals(v);
                default:
                    break;
            }
            if (v == null) {
                return false;
            }
            switch (op) {
                case eq:
                    return same(v, value);
                case in:
                    return values.stream().anyMatch(x -> same(v, x));
                default:
                    break;
            }
            BigDecimal n = number(v);
            if (n == null) {
                return false;
            }
            BigDecimal limit = value == null ? BigDecimal.ZERO : number(value);
            if (limit == null) {
                return false;
            }
            int c = n.compareTo(limit);
            return switch (op) {
                case gt -> c > 0;
                case gte -> c >= 0;
                case lt -> c < 0;
                case lte -> c <= 0;
                default -> false;
            };
        }

        @Override
        public Set<String> factIds() {
            return Set.of(fact);
        }

        @Override
        public Map<String, Object> toTree() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", op.name());
            m.put("fact", fact);
            switch (op) {
                case in:
                    m.put("values", values);
                    break;
                case eq:
                case gt:
                case gte:
                case lt:
                case lte:
                    m.put("value", value);
                    break;
                default:
                    break;
            }
            return m;
        }
    }

    /** Parses an expression tree (plain maps and lists, as JSON gives them); null parses to null (always). */
    static Condition parse(Object tree) {
        if (tree == null) {
            return null;
        }
        if (!(tree instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("A condition is an object");
        }
        if (m.containsKey("all")) {
            return new All(parseAll(m.get("all")));
        }
        if (m.containsKey("any")) {
            return new Any(parseAll(m.get("any")));
        }
        if (m.containsKey("not")) {
            return new Not(parse(m.get("not")));
        }
        Object op = m.get("op");
        Object fact = m.get("fact");
        if (!(op instanceof String name) || !(fact instanceof String id)) {
            throw new IllegalArgumentException("A condition leaf needs op and fact");
        }
        Op parsed;
        try {
            parsed = Op.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown condition operator " + name, ex);
        }
        List<Object> values = m.get("values") instanceof List<?> l ? new ArrayList<Object>(l) : List.of();
        return new Leaf(parsed, id, m.get("value"), values);
    }

    private static List<Condition> parseAll(Object list) {
        if (!(list instanceof List<?> l)) {
            throw new IllegalArgumentException("all/any take a list");
        }
        List<Condition> out = new ArrayList<>(l.size());
        for (Object o : l) {
            out.add(parse(o));
        }
        return out;
    }

    /** Fact ids of a possibly-null condition. */
    static Set<String> factIdsOf(Condition c) {
        return c == null ? Set.of() : c.factIds();
    }

    private static Set<String> union(List<Condition> parts) {
        Set<String> out = new LinkedHashSet<>();
        parts.forEach(p -> out.addAll(p.factIds()));
        return out;
    }

    /** The value as a decimal, or null when it is not a number (booleans and text are not numbers). */
    static BigDecimal number(Object v) {
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return null;
    }

    /** Equality where numbers compare by value (2 equals 2.0) and everything else by equals. */
    static boolean same(Object a, Object b) {
        BigDecimal x = number(a);
        BigDecimal y = number(b);
        if (x != null && y != null) {
            return x.compareTo(y) == 0;
        }
        return a != null && a.equals(b);
    }
}
