package com.afrotransact.orders.adapter.out.module;

import com.afrotransact.deliveryruns.api.DeliveryRunsApi;
import com.afrotransact.orders.domain.FeeRuleSnapshot;

/** The fee rule delivery-runs answers, as the snapshot orders keeps on the order (null when missing or incomplete, as over HTTP). */
final class DeliveryRulesMapping {

    private DeliveryRulesMapping() {
    }

    static FeeRuleSnapshot snapshot(DeliveryRunsApi.FeeRule rule) {
        if (rule == null || rule.method() == null || rule.method().isBlank()) {
            return null;
        }
        return new FeeRuleSnapshot(rule.method(), blankToNull(rule.ruleId()), rule.ruleVersion(),
                rule.currency() == null ? "USD" : rule.currency(), rule.amountMinor(),
                rule.source() == null || rule.source().isBlank() ? "RULE" : rule.source(), blankToNull(rule.band()),
                rule.distanceMiles());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
