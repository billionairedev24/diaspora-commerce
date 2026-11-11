package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.seller.application.SellingViews.ApprovalRow;
import com.afrotransact.seller.application.SellingViews.Capabilities;
import com.afrotransact.seller.application.SellingViews.Capability;
import com.afrotransact.seller.application.SellingViews.Notice;
import com.afrotransact.seller.application.SellingViews.OnFile;
import com.afrotransact.seller.application.SellingViews.SellingApprovals;
import com.afrotransact.seller.application.SellingViews.Slot;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.SellingApproval;
import com.afrotransact.seller.domain.SellingApprovalStatus;
import com.afrotransact.seller.domain.SellingEvaluator;
import com.afrotransact.seller.domain.SellingEvaluator.Filed;
import com.afrotransact.seller.domain.SellingFacts;
import com.afrotransact.seller.domain.SellingLevel;
import com.afrotransact.seller.domain.SellingRule;
import com.afrotransact.seller.domain.SellingScope;
import com.afrotransact.seller.domain.SellingScopeType;
import com.afrotransact.seller.domain.SellingStatus;
import com.afrotransact.seller.domain.SellingStatus.Missing;
import com.afrotransact.seller.port.SellingApplicationRepository;
import com.afrotransact.seller.port.SellingApprovalRepository;
import com.afrotransact.seller.port.SellingRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellingStatusServiceImpl implements SellingStatusService {

    static final Set<String> MEMBERS = Set.of("owner", "admin", "fulfilment", "catalog", "finance", "support", "ads",
            "marketing");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final SellingRuleRepository rules;
    private final SellingApprovalRepository approvals;
    private final SellingApplicationRepository applications;
    private final AccessGuard access;
    private final SellerSettings settings;
    private final Clock clock;

    SellingStatusServiceImpl(SellingRuleRepository rules, SellingApprovalRepository approvals,
                             SellingApplicationRepository applications, AccessGuard access, SellerSettings settings,
                             Clock clock) {
        this.rules = rules;
        this.approvals = approvals;
        this.applications = applications;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public SellingStatus status(String orgId, SellingFacts facts) {
        authorise(orgId);
        if (settings.isRetail(orgId)) {
            return SellingStatus.sell();
        }
        List<SellingScope> scopes = facts.candidateScopes();
        List<SellingRule> found = new ArrayList<>(rules.findActive(scopes));
        List<SellingScope> capabilities = found.stream().filter(r -> r.capability() != null)
                .map(r -> SellingScope.capability(r.capability())).distinct().toList();
        found.addAll(rules.findActive(capabilities));
        List<SellingScope> approvalScopes = new ArrayList<>(scopes);
        approvalScopes.addAll(capabilities);
        Map<SellingScope, SellingApproval> have = approvals.find(orgId, approvalScopes);
        Map<SellingScope, Filed> filed = applications.latest(orgId, approvalScopes);
        return SellingEvaluator.evaluate(facts, found, have, filed, Instant.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public SellingApprovals approvals(String orgId) {
        authorise(orgId);
        Instant now = Instant.now(clock);
        List<SellingRule> gated = rules.findGated();
        Map<SellingScope, SellingApproval> have = new HashMap<>();
        approvals.forOrg(orgId).forEach(a -> have.put(a.scope(), a));
        List<SellingScope> scopes = gated.stream().map(SellingRule::scope).toList();
        Map<SellingScope, Filed> filed = applications.latest(orgId, scopes);
        boolean retail = settings.isRetail(orgId);
        List<ApprovalRow> rows = new ArrayList<>();
        List<Notice> notices = new ArrayList<>();
        for (SellingRule rule : gated) {
            SellingApproval a = have.get(rule.scope());
            if (rule.level() == SellingLevel.NOT_AVAILABLE) {
                rows.add(row(rule, null, filed.get(rule.scope()), now, retail));
                continue;
            }
            if (rule.capability() != null && rule.scope().type() == SellingScopeType.CATEGORY) {
                continue; // covered by the capability row (one approval for every food service category)
            }
            rows.add(row(rule, a, filed.get(rule.scope()), now, retail));
            if (a != null) {
                notices.addAll(noticesOf(a, filed.get(rule.scope()), now));
            }
        }
        return new SellingApprovals(rows, notices, capabilities(orgId, have, now, retail));
    }

    @Override
    @Transactional(readOnly = true)
    public Capabilities capabilities(String orgId) {
        authorise(orgId);
        Instant now = Instant.now(clock);
        Map<SellingScope, SellingApproval> have = new HashMap<>();
        approvals.forOrg(orgId).forEach(a -> have.put(a.scope(), a));
        return capabilities(orgId, have, now, settings.isRetail(orgId));
    }

    private Capabilities capabilities(String orgId, Map<SellingScope, SellingApproval> have, Instant now,
                                      boolean retail) {
        SellingApproval food = have.get(SellingScope.capability("FOOD_SERVICE"));
        if (retail) {
            return new Capabilities(new Capability(true, "APPROVED", null));
        }
        if (food == null) {
            return new Capabilities(new Capability(false, "NOT_APPLIED", null));
        }
        return new Capabilities(new Capability(food.valid(now), food.expired(now) ? "EXPIRED" : food.status().name(),
                food.expiresAt()));
    }

    private ApprovalRow row(SellingRule rule, SellingApproval a, Filed filed, Instant now, boolean retail) {
        SellingApprovalStatus state;
        String message;
        boolean canApply = false;
        if (rule.level() == SellingLevel.NOT_AVAILABLE) {
            state = SellingApprovalStatus.NOT_AVAILABLE;
            message = rule.reason();
        } else if (retail) {
            state = SellingApprovalStatus.APPROVED;
            message = "AfroTransact Retail sells everywhere.";
        } else {
            SellingStatus one = SellingEvaluator.evaluate(factsFor(rule), List.of(rule),
                    a == null ? Map.of() : Map.of(rule.scope(), a), filed == null ? Map.of()
                    : Map.of(rule.scope(), filed), now);
            Missing m = one.missing().isEmpty() ? null : one.missing().getFirst();
            if (m == null) {
                state = SellingApprovalStatus.APPROVED;
                message = "You are approved" + (a != null && a.expiresAt() != null
                        ? ". It runs until " + DAY.format(a.expiresAt().atZone(ZoneOffset.UTC)) : "") + ".";
            } else {
                state = m.state();
                message = m.message();
                canApply = m.canApply();
            }
        }
        return new ApprovalRow(rule.scope().type().name(), rule.scope().key(), rule.label(), rule.level().name(),
                state.name(), state.words(), message, canApply, a == null ? null : a.approvedAt(),
                a == null ? null : a.expiresAt(), a == null ? null : a.daysToExpiry(now),
                a == null ? null : a.reapplyAfter(), rule.documents().stream().map(Slot::of).toList(),
                a == null ? List.of() : a.documents().stream().map(OnFile::of).toList(),
                filed == null ? null : filed.id(), filed == null ? null : filed.status().name(),
                rule.note(), a != null && a.listingsPaused());
    }

    /** Facts that make a rule apply, so that one rule can be evaluated alone (conditional rules count as applying). */
    private static SellingFacts factsFor(SellingRule rule) {
        return new SellingFacts(null, null, null, rule.materials().stream().findFirst().orElse(null), null,
                rule.watch());
    }

    private List<Notice> noticesOf(SellingApproval a, Filed filed, Instant now) {
        List<Notice> out = new ArrayList<>();
        String label = a.label();
        Long days = a.daysToExpiry(now);
        if (a.status() == SellingApprovalStatus.APPROVED && days != null && days >= 0 && days <= 30 && !a.paused()) {
            out.add(new Notice(days <= 7 ? "urgent" : "warning", a.scope().key(), label, "A document for " + label
                    + " runs out in " + days + (days == 1 ? " day" : " days") + " (" + DAY.format(a.expiresAt()
                    .atZone(ZoneOffset.UTC)) + "). Upload the renewed one to keep selling."));
        }
        if (a.expired(now) && !a.listingsPaused()) {
            out.add(new Notice("urgent", a.scope().key(), label, "Your approval for " + label + " expired. Your "
                    + "listings stay up until " + DAY.format(a.graceEndsAt().atZone(ZoneOffset.UTC))
                    + ", then they are paused. Nothing is deleted; they return when you renew."));
        }
        if (a.listingsPaused()) {
            out.add(new Notice("urgent", a.scope().key(), label, "Your listings in " + label + " are paused"
                    + (a.paused() && a.pauseReason() != null ? ": " + a.pauseReason() : " until you renew")
                    + ". Nothing is deleted."));
        }
        if (filed != null && filed.status() == ApplicationStatus.INFO_REQUESTED) {
            out.add(new Notice("action", a.scope().key(), label, "We need more from you for " + label + "."));
        }
        return out;
    }

    private void authorise(String orgId) {
        Actor actor = access.current();
        boolean service = actor.type() == PrincipalType.SERVICE && actor.can(CatalogScopeService.SERVICE_READ);
        if (!service) {
            access.orgRoleOrStaff(orgId, MEMBERS, SellerPermissions.APPLICATIONS_REVIEW,
                    SellerPermissions.APPLICATIONS_APPROVE, SellerPermissions.APPLICATIONS_ASSIGN,
                    SellerPermissions.SELLERS_ENFORCE);
        }
    }
}
