package com.afrotransact.seller.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * How a plan charges its fee, configured on the plan so staff do not set it organisation by organisation:
 * <ol>
 *   <li>{@code freeMonths}: nothing is charged for the first N months of the subscription (0 = none);</li>
 *   <li>then {@code then} decides:
 *     <ul>
 *       <li>{@link Then#CHARGE}: the fee is charged every period;</li>
 *       <li>{@link Then#CHARGE_WHEN}: charging starts only once the {@code condition} is met, and stays on;</li>
 *       <li>{@link Then#WAIVE_WHILE}: each period is free while the {@code condition} is met (for example, while the
 *           seller keeps 20 live listings or sells $1,000 a month).</li>
 *     </ul></li>
 * </ol>
 * A condition is one or more clauses ({@code metric >= threshold}) that must all hold ({@link Match#ALL}) or any one
 * ({@link Match#ANY}). Sales and order counts are measured since the subscription started, or over the last
 * {@code windowMonths} when set. A waived period still gets a $0 invoice that says why.
 *
 * @param windowMonths null = since the subscription started
 */
public record PlanChargePolicy(int freeMonths, Then then, Match match, List<Clause> clauses, Integer windowMonths) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    public static final int MAX_FREE_MONTHS = 36;
    public static final int MAX_CLAUSES = 5;
    public static final int MAX_WINDOW_MONTHS = 24;

    /** The charge-always policy. */
    public static final PlanChargePolicy ALWAYS = new PlanChargePolicy(0, Then.CHARGE, Match.ALL, List.of(), null);

    public enum Then { CHARGE, CHARGE_WHEN, WAIVE_WHILE }

    public enum Match { ALL, ANY }

    /** {@code metric >= threshold}. */
    public record Clause(RewardMetric metric, long threshold) {
    }

    /**
     * What the policy says about a period starting now.
     *
     * @param charge whether the plan fee is charged
     * @param reason why not (shown on the $0 invoice); null when charged
     * @param nowMet a CHARGE_WHEN condition was met by this evaluation (remember it: charging stays on)
     */
    public record Decision(boolean charge, String reason, boolean nowMet) {
    }

    public PlanChargePolicy {
        then = then == null ? Then.CHARGE : then;
        match = match == null ? Match.ALL : match;
        clauses = clauses == null ? List.of() : List.copyOf(clauses);
    }

    /** Whether this policy can ever waive a charge (a plain {@link #ALWAYS} cannot). */
    public boolean isPlainCharge() {
        return freeMonths == 0 && then == Then.CHARGE;
    }

    /** Whether the condition is read to decide (and so needs the metrics). */
    public boolean needsMetrics() {
        return then != Then.CHARGE && !clauses.isEmpty();
    }

    /**
     * @param subscribedAt when the subscription's billing started
     * @param value        reads a clause's metric now ({@link RewardMetric}; sums honour {@code windowMonths})
     * @param alreadyMet   a CHARGE_WHEN condition was met before: charging stays on
     */
    public Decision decide(Instant subscribedAt, Instant now, ToLongFunction<Clause> value, boolean alreadyMet) {
        if (freeMonths > 0) {
            Instant freeUntil = subscribedAt.atOffset(ZoneOffset.UTC).plusMonths(freeMonths).toInstant();
            if (now.isBefore(freeUntil)) {
                return new Decision(false, "Free for your first " + (freeMonths == 1 ? "month" : freeMonths
                        + " months") + " (until " + DAY.format(freeUntil.atZone(ZoneOffset.UTC)) + ")", false);
            }
        }
        return switch (then) {
            case CHARGE -> new Decision(true, null, false);
            case CHARGE_WHEN -> {
                if (alreadyMet || clauses.isEmpty()) {
                    yield new Decision(true, null, false);
                }
                yield holds(value) ? new Decision(true, null, true)
                        : new Decision(false, "Charges start once you " + describe(value), false);
            }
            case WAIVE_WHILE -> holds(value) && !clauses.isEmpty()
                    ? new Decision(false, "Waived while you " + describe(value), false)
                    : new Decision(true, null, false);
        };
    }

    private boolean holds(ToLongFunction<Clause> value) {
        if (clauses.isEmpty()) {
            return false;
        }
        return match == Match.ALL ? clauses.stream().allMatch(c -> value.applyAsLong(c) >= c.threshold())
                : clauses.stream().anyMatch(c -> value.applyAsLong(c) >= c.threshold());
    }

    /** "reach $1,000 in sales (you're at $420) and have 20 active listings (you have 12)". */
    private String describe(ToLongFunction<Clause> value) {
        List<String> parts = new ArrayList<>();
        for (Clause c : clauses) {
            long now = value.applyAsLong(c);
            parts.add((c.metric() == RewardMetric.ACTIVE_LISTINGS ? "have " : "reach ") + c.metric().format(c.threshold())
                    + " " + c.metric().noun() + " (you're at " + c.metric().format(now) + ")");
        }
        return String.join(match == Match.ALL ? " and " : " or ", parts)
                + (windowMonths != null && clauses.stream().anyMatch(c -> c.metric().summed())
                ? ", counted over the last " + (windowMonths == 1 ? "month" : windowMonths + " months") : "");
    }

    /** The policy in a sentence for the plan card and the staff table: "3 months free, then $39/mo". */
    public String summary(String priceLabel) {
        List<String> parts = new ArrayList<>();
        if (freeMonths > 0) {
            parts.add(freeMonths == 1 ? "First month free" : "First " + freeMonths + " months free");
        }
        switch (then) {
            case CHARGE -> parts.add(freeMonths > 0 ? "then " + priceLabel : priceLabel);
            case CHARGE_WHEN -> parts.add((freeMonths > 0 ? "then " : "") + "free until you " + conditionWords());
            case WAIVE_WHILE -> parts.add((freeMonths > 0 ? "then " : "") + priceLabel + ", free while you "
                    + conditionWords());
        }
        return String.join(", ", parts);
    }

    private String conditionWords() {
        List<String> parts = new ArrayList<>();
        for (Clause c : clauses) {
            parts.add((c.metric() == RewardMetric.ACTIVE_LISTINGS ? "have " : "reach ") + c.metric().format(
                    c.threshold()) + " " + c.metric().noun());
        }
        return String.join(match == Match.ALL ? " and " : " or ", parts);
    }

    /** Field errors (keys under {@code chargePolicy.}); empty when the policy is valid. */
    public Map<String, String> validate() {
        Map<String, String> e = new LinkedHashMap<>();
        if (freeMonths < 0 || freeMonths > MAX_FREE_MONTHS) {
            e.put("chargePolicy.freeMonths", "0 to " + MAX_FREE_MONTHS + " months");
        }
        if (then != Then.CHARGE && clauses.isEmpty()) {
            e.put("chargePolicy.clauses", "Add the condition (for example, 20 active listings)");
        }
        if (clauses.size() > MAX_CLAUSES) {
            e.put("chargePolicy.clauses", "Up to " + MAX_CLAUSES + " conditions");
        }
        for (Clause c : clauses) {
            if (c.metric() == null || c.threshold() <= 0) {
                e.put("chargePolicy.clauses", "Each condition needs a measure and an amount above zero");
            } else if (c.metric() == RewardMetric.DAYS_SINCE_APPROVAL) {
                e.put("chargePolicy.clauses", "Use the free months for a stretch of time");
            }
        }
        if (windowMonths != null && (windowMonths < 1 || windowMonths > MAX_WINDOW_MONTHS)) {
            e.put("chargePolicy.windowMonths", "1 to " + MAX_WINDOW_MONTHS + " months");
        }
        return e;
    }
}
