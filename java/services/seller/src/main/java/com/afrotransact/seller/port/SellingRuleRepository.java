package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.SellingLevel;
import com.afrotransact.seller.domain.SellingRule;
import com.afrotransact.seller.domain.SellingScope;
import com.afrotransact.seller.domain.SellingScopeType;

/** Selling rules (what it takes to sell in a scope) and their audit trail. */
public interface SellingRuleRepository {

    /** Active rules for these scopes (any level). */
    List<SellingRule> findActive(Collection<SellingScope> scopes);

    /** Every active rule that is not plain SELL: what the Selling approvals page lists. */
    List<SellingRule> findGated();

    Optional<SellingRule> find(UUID id);

    Optional<SellingRule> find(SellingScope scope);

    Page<SellingRule> search(RuleSearch search);

    SellingRule insert(SellingRule rule);

    /** Optimistic: fails with a conflict when the rule changed since it was read. */
    SellingRule update(SellingRule rule);

    void audit(RuleAudit audit);

    List<RuleAudit> audits(UUID ruleId);

    /** @param level null for every level; @param text matches the label or key */
    record RuleSearch(SellingScopeType type, SellingLevel level, String text, boolean activeOnly, int page, int size) {
    }

    /** One change to a rule: who, why (10+ characters), what it was and what it became (stored as JSON). */
    record RuleAudit(UUID id, UUID ruleId, String action, String actorId, String actorName, String reason,
                     Object before, Object after, Instant changedAt) {
    }
}
