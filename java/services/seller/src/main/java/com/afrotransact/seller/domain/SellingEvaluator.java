package com.afrotransact.seller.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides SELL, APPLY or NOT_AVAILABLE for one listing and one organisation (docs/engineering/listing-rules.md
 * section 2). Pure: the caller loads the rules and the organisation's approvals.
 */
public final class SellingEvaluator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private SellingEvaluator() {
    }

    /** The application the organisation has (or last had) for a scope, for the status line. */
    public record Filed(String id, ApplicationStatus status) {
    }

    /**
     * @param rules     every active rule of the candidate scopes, plus the capability rules they refer to
     * @param approvals the organisation's approvals by scope
     * @param filed     the organisation's latest application per scope
     */
    public static SellingStatus evaluate(SellingFacts facts, List<SellingRule> rules,
                                         Map<SellingScope, SellingApproval> approvals, Map<SellingScope, Filed> filed,
                                         Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Map<SellingScope, SellingRule> byScope = new HashMap<>();
        rules.forEach(r -> byScope.put(r.scope(), r));
        List<SellingStatus.SellingMissing> missing = new ArrayList<>();
        Set<String> pending = new LinkedHashSet<>();
        Set<SellingScope> seen = new LinkedHashSet<>();
        rules.stream().filter(r -> r.active() && r.level() != SellingLevel.SELL
                && r.scope().type() != SellingScopeType.CAPABILITY)
                .sorted(Comparator.comparing((SellingRule r) -> r.scope().type()).thenComparing(r -> r.scope().key()))
                .forEach(rule -> {
                    switch (rule.appliesTo(facts)) {
                        case NO -> { }
                        case UNKNOWN -> pending.add("material");
                        case YES -> {
                            if (rule.level() == SellingLevel.NOT_AVAILABLE) {
                                missing.add(prohibited(rule));
                            } else {
                                SellingRule need = rule.capability() == null ? rule
                                        : byScope.getOrDefault(SellingScope.capability(rule.capability()), rule);
                                if (seen.add(need.scope())) {
                                    approvalNeed(need, approvals.get(need.scope()), filed.get(need.scope()), now,
                                            today).ifPresent(missing::add);
                                }
                            }
                        }
                    }
                });
        if (missing.isEmpty()) {
            return new SellingStatus(SellingLevel.SELL, null, List.of(), List.copyOf(pending));
        }
        SellingStatus.SellingMissing blocked = missing.stream().filter(m -> m.level() == SellingLevel.NOT_AVAILABLE)
                .findFirst().orElse(null);
        if (blocked != null) {
            return new SellingStatus(SellingLevel.NOT_AVAILABLE, blocked.message(), missing, List.copyOf(pending));
        }
        return new SellingStatus(SellingLevel.APPLY, missing.getFirst().message(), missing, List.copyOf(pending));
    }

    private static SellingStatus.SellingMissing prohibited(SellingRule rule) {
        String why = rule.reason() == null || rule.reason().isBlank() ? rule.label() + " is not available on AfroTransact."
                : rule.reason();
        return new SellingStatus.SellingMissing(rule.scope(), rule.label(), SellingLevel.NOT_AVAILABLE,
                SellingApprovalStatus.NOT_AVAILABLE, why, false, null, List.of(), null, null);
    }

    private static Optional<SellingStatus.SellingMissing> approvalNeed(SellingRule rule, SellingApproval a,
                                                                          Filed filed, Instant now, LocalDate today) {
        String label = rule.label();
        String appId = filed == null ? null : filed.id();
        String appStatus = filed == null ? null : filed.status().name();
        if (a != null && a.valid(now)) {
            return Optional.empty();
        }
        if (a != null && a.status() == SellingApprovalStatus.APPROVED && a.paused()) {
            return Optional.of(new SellingStatus.SellingMissing(rule.scope(), label, SellingLevel.NOT_AVAILABLE,
                    SellingApprovalStatus.NOT_AVAILABLE, "Your approval for " + label + " is paused: "
                    + orElse(a.pauseReason(), "our team is reviewing your account") + ".", false, null,
                    rule.documents(), appId, appStatus));
        }
        if (a != null && a.status() == SellingApprovalStatus.NOT_AVAILABLE
                && (a.reapplyAfter() == null || today.isBefore(a.reapplyAfter()))) {
            String when = a.reapplyAfter() == null ? "This cannot be appealed."
                    : "This cannot be appealed. You can apply again from " + DAY.format(a.reapplyAfter()) + ".";
            return Optional.of(new SellingStatus.SellingMissing(rule.scope(), label, SellingLevel.NOT_AVAILABLE,
                    SellingApprovalStatus.NOT_AVAILABLE, label + " is not available to you: "
                    + orElse(a.reason(), "your account does not qualify") + ". " + when, false, a.reapplyAfter(),
                    rule.documents(), appId, appStatus));
        }
        SellingApprovalStatus state;
        String message;
        boolean canApply = true;
        if (a != null && a.status() == SellingApprovalStatus.APPLIED) {
            state = SellingApprovalStatus.APPLIED;
            message = "Your application for " + label + " is with our team. We aim to answer within 2 business days.";
            canApply = false;
        } else if (a != null && a.status() == SellingApprovalStatus.NEEDS_ATTENTION) {
            state = SellingApprovalStatus.NEEDS_ATTENTION;
            boolean update = filed != null && filed.status() == ApplicationStatus.INFO_REQUESTED;
            message = update ? "We need more from you for " + label + ": " + orElse(a.reason(), "see your application")
                    + "." : "Your application for " + label + " was not approved: " + orElse(a.reason(), "see your application")
                    + ". You can apply again.";
        } else if (a != null && a.expired(now)) {
            state = SellingApprovalStatus.EXPIRED;
            message = "Your approval for " + label + " expired"
                    + (a.expiresAt() == null ? "" : " on " + DAY.format(a.expiresAt().atZone(ZoneOffset.UTC)))
                    + ". Upload the renewed document to sell here again.";
        } else {
            state = SellingApprovalStatus.NOT_APPLIED;
            message = "You need approval to sell in " + label + ". Apply once; after that every listing here goes live.";
        }
        return Optional.of(new SellingStatus.SellingMissing(rule.scope(), label, SellingLevel.APPLY, state, message,
                canApply, a == null ? null : a.reapplyAfter(), rule.documents(), appId, appStatus));
    }

    private static String orElse(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
