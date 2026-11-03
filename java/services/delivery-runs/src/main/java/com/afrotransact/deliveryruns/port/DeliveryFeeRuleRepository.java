package com.afrotransact.deliveryruns.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.deliveryruns.domain.DeliveryFeeChange;
import com.afrotransact.deliveryruns.domain.DeliveryFeeRule;
import com.afrotransact.deliveryruns.domain.FeeMethod;

/** Staff-managed delivery fee rules and their audit trail (ADR 0005 §3). */
public interface DeliveryFeeRuleRepository {

    /** Every rule, by method then currency. */
    List<DeliveryFeeRule> findAll();

    Optional<DeliveryFeeRule> find(FeeMethod method, String currency);

    /** The rule locked for update, so concurrent staff edits queue. */
    Optional<DeliveryFeeRule> lock(FeeMethod method, String currency);

    /** Stores the first version of a rule. */
    void insert(DeliveryFeeRule rule);

    /** Stores {@code next} (version + 1) when the stored version is {@code next.version() - 1}, else throws. */
    void update(DeliveryFeeRule next);

    /** Appends to the audit trail. */
    void log(DeliveryFeeChange change);

    /** Changes of one rule (or of every rule when {@code method} is null), newest first. */
    List<DeliveryFeeChange> changes(FeeMethod method, String currency, int limit);
}
