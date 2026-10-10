package com.afrotransact.payments.domain;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimum payout (default USD 25), one request per day (failed payouts do not count), never more than available,
 * and only owner / finance roles of the organisation (or AfroTransact staff) may request.
 */
public class DefaultPayoutPolicy implements PayoutPolicy {

    /** Organisation roles allowed to request payouts. */
    public static final Set<String> DEFAULT_ROLES = Set.of("seller.owner", "seller.finance", "provider.owner",
            "provider.finance");

    private final long minimumMinor;
    private final Set<String> allowedRoles;

    public DefaultPayoutPolicy(long minimumMinor, Set<String> allowedRoles) {
        if (minimumMinor < 1) {
            throw new IllegalArgumentException("minimum must be positive");
        }
        this.minimumMinor = minimumMinor;
        this.allowedRoles = Set.copyOf(allowedRoles);
    }

    @Override
    public Money minimum(String currency) {
        return Money.of(minimumMinor, currency);
    }

    @Override
    public Money validate(Requester requester, PayoutAsk ask, Money available, List<PayoutRequest> requestsToday) {
        if (!requester.staff() && Collections.disjoint(requester.roles(), allowedRoles)) {
            throw new PaymentsForbidden("payments.payout-role", "Only the owner or a finance member of the "
                    + "organisation can request payouts; ask them to request it");
        }
        if (!canRequestToday(requestsToday)) {
            throw new PaymentsRuleViolation("payments.payout-daily-limit",
                    "Only one payout request per day; try again tomorrow");
        }
        Money amount = ask.all() ? available : ask.amount();
        if (amount == null) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Give an amount or request all",
                    Map.of("amountMinor", "Required unless all=true"));
        }
        amount.requireSameCurrency(available);
        Money minimum = minimum(available.currency());
        if (amount.lessThan(minimum)) {
            throw new PaymentsRuleViolation("payments.payout-below-minimum", "The minimum payout is " + minimum
                    + (ask.all() ? "; available is only " + available : ""), Map.of("amountMinor", "Below minimum"));
        }
        if (amount.greaterThan(available)) {
            throw new PaymentsRuleViolation("payments.payout-exceeds-available", "Only " + available
                    + " is available to request", Map.of("amountMinor", "Exceeds available"));
        }
        return amount;
    }

    @Override
    public boolean canRequestToday(List<PayoutRequest> requestsToday) {
        return requestsToday.stream().noneMatch(r -> r.status().consumesBalance());
    }
}
