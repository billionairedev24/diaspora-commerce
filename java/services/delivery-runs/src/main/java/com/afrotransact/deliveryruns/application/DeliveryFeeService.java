package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.Optional;

import com.afrotransact.deliveryruns.domain.DeliveryFeeChange;
import com.afrotransact.deliveryruns.domain.DeliveryFeeRule;
import com.afrotransact.deliveryruns.domain.FeeMethod;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;

/**
 * Delivery fees as configuration (ADR 0005 §3): staff manage one rule per delivery method and currency, and every
 * checkout resolves a fee from them. Reads need {@code delivery-runs:read}; changes {@code preorders:manage} and a
 * reason, logged with who and when. Resolving for orders-service needs {@code delivery-runs:reserve}.
 */
public interface DeliveryFeeService {

    /** Every rule, by method then currency. */
    List<DeliveryFeeRule> rules();

    /**
     * Sets a rule's numbers (creating its first version when there is none). {@code expectedVersion} is the version the
     * caller saw (null = any; 0 = none yet). 422 {@code delivery-runs.reason-required} without a reason, and for numbers
     * that do not form a valid rule; 409 {@code delivery-runs.version-mismatch} when the rule changed meanwhile.
     */
    DeliveryFeeRule replace(FeeMethod method, String currency, FeeRuleCommand command, Long expectedVersion);

    /** The audit trail, newest first: one rule's when {@code method} is given, else every rule's. */
    List<DeliveryFeeChange> changes(FeeMethod method, String currency, int limit);

    /**
     * What a customer would pay: the resolution checkout runs. Empty when no rule can price it (no rule for the method
     * and currency, or beyond how far AfroTransact delivers).
     */
    Optional<FeeSnapshot> preview(FeePreviewCommand command);

    /** The carrier rate for an order shipped beyond coverage (orders-service), as {@link #preview} with the quote. */
    Optional<FeeSnapshot> carrier(String currency, Long carrierQuoteMinor, long itemsMinor);
}
