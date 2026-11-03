package com.afrotransact.payments.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One rate section of a fee-schedule version: the default rate, the rate per category and the rate per plan (plan
 * code → category → bps, where category {@code *} is the plan's rate for categories it does not list). The product
 * commission section and the service connection-fee section ({@code connectionFee}) have the same shape.
 */
public record RateTable(int defaultBps, Map<String, Integer> categoryBps, Map<String, Map<String, Integer>> planBps) {

    public static final String ANY = CommissionOverride.ANY;

    public RateTable {
        FeeSchedule.requireBps(defaultBps, "default");
        categoryBps = normalise(categoryBps == null ? Map.of() : categoryBps, "category ");
        Map<String, Map<String, Integer>> plans = new LinkedHashMap<>();
        if (planBps != null) {
            planBps.forEach((plan, rates) -> {
                if (plan == null || plan.isBlank()) {
                    throw new PaymentsRuleViolation("payments.invalid-fee-schedule", "A plan rate needs a plan code");
                }
                String code = plan.trim().toLowerCase(Locale.ROOT);
                Map<String, Integer> r = normalise(rates == null ? Map.of() : rates, "plan " + code + " ");
                if (!r.isEmpty()) {
                    plans.put(code, r);
                }
            });
        }
        planBps = Map.copyOf(plans);
    }

    private static Map<String, Integer> normalise(Map<String, Integer> in, String what) {
        Map<String, Integer> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (k == null || k.isBlank() || v == null) {
                throw new PaymentsRuleViolation("payments.invalid-fee-schedule", "Empty category or rate in " + what);
            }
            String key = k.trim().toLowerCase(Locale.ROOT);
            FeeSchedule.requireBps(v, what + key);
            out.put(key, v);
        });
        return Map.copyOf(out);
    }

    /** The plan's rate for the category, else the plan's {@code *} rate; empty when the plan has neither. */
    public Optional<Integer> planRate(String planCode, String category) {
        if (planCode == null || planCode.isBlank()) {
            return Optional.empty();
        }
        Map<String, Integer> rates = planBps.get(planCode.trim().toLowerCase(Locale.ROOT));
        if (rates == null) {
            return Optional.empty();
        }
        Integer exact = category == null || category.isEmpty() ? null : rates.get(category);
        return Optional.ofNullable(exact != null ? exact : rates.get(ANY));
    }
}
