package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.application.SellingViews.RuleView;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.SellingDocumentKind;
import com.afrotransact.seller.domain.SellingDocumentRequirement;
import com.afrotransact.seller.domain.SellingLevel;
import com.afrotransact.seller.domain.SellingRule;
import com.afrotransact.seller.domain.SellingScope;
import com.afrotransact.seller.domain.SellingScopeType;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.SellingRuleRepository;
import com.afrotransact.seller.port.SellingRuleRepository.RuleAudit;
import com.afrotransact.seller.port.SellingRuleRepository.RuleSearch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellingRuleAdminServiceImpl implements SellingRuleAdminService {

    private static final int MIN_REASON = 10;
    private static final int MIN_SELLER_TEXT = 5;

    private final SellingRuleRepository rules;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final Clock clock;

    SellingRuleAdminServiceImpl(SellingRuleRepository rules, AccessGuard access, DomainEventPublisher events,
                                Clock clock) {
        this.rules = rules;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RuleView> search(String scopeType, String level, String text, boolean activeOnly, int page,
                                 int size) {
        viewer();
        Page<SellingRule> found = rules.search(new RuleSearch(parseType(scopeType), parseLevel(level), text,
                activeOnly, Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        return new Page<>(found.items().stream().map(RuleView::of).toList(), found.page(), found.size(),
                found.total());
    }

    @Override
    @Transactional(readOnly = true)
    public RuleView get(UUID id) {
        viewer();
        return RuleView.of(rules.find(id).orElseThrow(() -> notFound(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleAudit> audit(UUID id) {
        viewer();
        return rules.audits(id);
    }

    @Override
    @Transactional
    public RuleView create(RuleInput in, String reason, String actorName) {
        Actor actor = access.staff(SellerPermissions.SELLING_RULES_MANAGE);
        String why = reason(reason);
        SellingScopeType type = requireType(in.scopeType());
        SellingScope scope = scope(type, in);
        if (rules.find(scope).isPresent()) {
            throw new SellerStateConflict("seller.selling-rule-exists", "There is already a rule for that scope. "
                    + "Edit it instead.");
        }
        Instant now = Instant.now(clock);
        SellingLevel level = requireLevel(in.level());
        SellingRule rule = new SellingRule(UUID.randomUUID(), scope, label(in, scope), level, docs(in), sellerText(in,
                level), text(in.note()), null, capability(in), materials(in), in.watch(), reapply(in),
                in.active() == null || in.active(), "STAFF", now, actorName, 0);
        SellingRule saved = rules.insert(rule);
        rules.audit(new RuleAudit(UUID.randomUUID(), saved.id(), "CREATE", actor.subject(), actorName, why, null,
                RuleView.of(saved), now));
        changed(saved, why, now);
        return RuleView.of(saved);
    }

    @Override
    @Transactional
    public RuleView update(UUID id, RuleInput in, String reason, String actorName) {
        Actor actor = access.staff(SellerPermissions.SELLING_RULES_MANAGE);
        String why = reason(reason);
        SellingRule current = rules.find(id).orElseThrow(() -> notFound(id));
        if (in.version() != null && in.version() != current.version()) {
            throw new SellerStateConflict("seller.concurrent-update",
                    "This rule changed since you opened it; reload it");
        }
        Instant now = Instant.now(clock);
        SellingLevel level = in.level() == null ? current.level() : requireLevel(in.level());
        SellingRule next = new SellingRule(current.id(), current.scope(),
                in.label() == null || in.label().isBlank() ? current.label() : in.label().strip(), level,
                in.documents().isEmpty() && in.level() == null ? current.documents() : docs(in),
                sellerText(in, level), in.note() == null ? current.note() : text(in.note()), current.approvalLevel(),
                current.scope().type() == SellingScopeType.CATEGORY ? capability(in) : null, materials(in), in.watch(),
                in.reapplyAfterDays() == null ? current.reapplyAfterDays() : reapply(in),
                in.active() == null ? current.active() : in.active(), "STAFF", now, actorName, current.version());
        if (same(current, next)) {
            return RuleView.of(current);
        }
        SellingRule saved = rules.update(next);
        rules.audit(new RuleAudit(UUID.randomUUID(), saved.id(), "UPDATE", actor.subject(), actorName, why,
                RuleView.of(current), RuleView.of(saved), now));
        changed(saved, why, now);
        return RuleView.of(saved);
    }

    private static boolean same(SellingRule a, SellingRule b) {
        return a.label().equals(b.label()) && a.level() == b.level() && a.documents().equals(b.documents())
                && a.reason().equals(b.reason()) && a.note().equals(b.note())
                && Objects.equals(a.capability(), b.capability()) && a.materials().equals(b.materials())
                && a.watch() == b.watch() && a.reapplyAfterDays() == b.reapplyAfterDays()
                && a.active() == b.active();
    }

    /** Tells catalog the rules of a scope changed, so it re-checks that scope's listings. */
    private void changed(SellingRule rule, String why, Instant now) {
        events.publish(SellerEvents.SELLING_RULE_CHANGED, SellerEvents.AGGREGATE_COMPLIANCE, rule.id().toString(),
                new SellingEventPayload(null, rule.scope().type().name(), rule.scope().key(), rule.label(), null, null,
                        rule.level().name(), "RULE", why, List.of(), false, null, null, null, List.of(), List.of(),
                        now, rule.scope().type() == SellingScopeType.CAPABILITY
                        ? rules.categoriesWithCapability(rule.scope().key()) : List.of()));
    }

    // ---------------------------------------------------------------- input

    private static SellingScope scope(SellingScopeType type, RuleInput in) {
        String key = in.key() == null ? "" : in.key().strip();
        SellingScope scope = switch (type) {
            case BRAND -> SellingScope.brand(key);
            case BRAND_IN_CATEGORY -> SellingScope.brandInCategory(key, in.categoryCode());
            case CATEGORY -> SellingScope.category(key);
            case PRODUCT_TYPE -> SellingScope.productType(key);
            case PRODUCT -> SellingScope.product(key);
            case CAPABILITY -> SellingScope.capability(key);
        };
        if (!SellingScope.validKey(type, scope.key())) {
            throw new SellerRuleViolation("seller.invalid-selling-scope", "That is not a valid "
                    + type.noun(), Map.of("key", "Not a valid " + type.noun()));
        }
        return scope;
    }

    private static String label(RuleInput in, SellingScope scope) {
        if (in.label() != null && !in.label().isBlank()) {
            return in.label().strip();
        }
        return scope.type() == SellingScopeType.BRAND || scope.type() == SellingScopeType.BRAND_IN_CATEGORY
                ? (in.key() == null ? scope.key() : in.key().strip()) : scope.key();
    }

    private static List<SellingDocumentRequirement> docs(RuleInput in) {
        Map<SellingDocumentKind, Boolean> out = new LinkedHashMap<>();
        for (SellingRuleAdminService.Doc d : in.documents()) {
            try {
                out.put(SellingDocumentKind.valueOf(d.kind().trim().toUpperCase(Locale.ROOT)), d.optional());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new SellerRuleViolation("seller.invalid-document-kind", "Unknown document type " + d.kind(),
                        Map.of("documents", "Unknown document type"));
            }
        }
        return out.entrySet().stream().map(e -> new SellingDocumentRequirement(e.getKey(), e.getValue())).toList();
    }

    private static String sellerText(RuleInput in, SellingLevel level) {
        String text = in.reason() == null ? "" : in.reason().strip();
        if (level == SellingLevel.NOT_AVAILABLE && text.length() < MIN_SELLER_TEXT) {
            throw new SellerRuleViolation("seller.reason-required",
                    "Say why it is not available: sellers read this", Map.of("reason", "Required"));
        }
        return text;
    }

    private static String capability(RuleInput in) {
        return in.capability() == null || in.capability().isBlank() ? null
                : SellingScope.capability(in.capability()).key();
    }

    private static Set<String> materials(RuleInput in) {
        return in.materials().stream().map(m -> m.strip().toLowerCase(Locale.ROOT)).filter(m -> !m.isEmpty())
                .collect(Collectors.toSet());
    }

    private static int reapply(RuleInput in) {
        int days = in.reapplyAfterDays() == null ? 30 : in.reapplyAfterDays();
        if (days < 1 || days > 730) {
            throw new SellerRuleViolation("seller.invalid-reapply-days", "Between 1 and 730 days",
                    Map.of("reapplyAfterDays", "Between 1 and 730"));
        }
        return days;
    }

    private static String text(String s) {
        return s == null ? "" : s.strip();
    }

    private static String reason(String reason) {
        if (reason == null || reason.strip().length() < MIN_REASON) {
            throw new SellerRuleViolation("seller.reason-required", "Say why (at least " + MIN_REASON
                    + " characters)", Map.of("reason", "Required"));
        }
        return reason.strip();
    }

    private static SellingScopeType requireType(String type) {
        SellingScopeType t = parseType(type);
        if (t == null) {
            throw new SellerRuleViolation("seller.invalid-selling-scope", "Choose what the rule is about",
                    Map.of("scopeType", "Required"));
        }
        return t;
    }

    private static SellingScopeType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return SellingScopeType.valueOf(type.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SellerRuleViolation("seller.invalid-selling-scope", "Unknown scope " + type,
                    Map.of("scopeType", "Unknown scope"));
        }
    }

    private static SellingLevel requireLevel(String level) {
        SellingLevel l = parseLevel(level);
        if (l == null) {
            throw new SellerRuleViolation("seller.invalid-selling-level", "Choose SELL, APPLY or NOT_AVAILABLE",
                    Map.of("level", "Required"));
        }
        return l;
    }

    private static SellingLevel parseLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        try {
            return SellingLevel.valueOf(level.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SellerRuleViolation("seller.invalid-selling-level", "Unknown level " + level,
                    Map.of("level", "Unknown level"));
        }
    }

    private void viewer() {
        access.staffAny(SellerPermissions.SELLING_RULES_MANAGE, SellerPermissions.APPLICATIONS_REVIEW,
                SellerPermissions.APPLICATIONS_APPROVE, SellerPermissions.APPLICATIONS_ASSIGN);
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("seller.selling-rule-not-found", "Selling rule " + id + " not found");
    }
}
