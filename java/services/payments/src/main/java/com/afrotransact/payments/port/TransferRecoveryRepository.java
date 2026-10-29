package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.TransferRecovery;
import com.afrotransact.payments.domain.TransferRecoveryStatus;

/** Amounts Stripe refused to take back from sellers' transfers, one row per (refund, transfer). */
public interface TransferRecoveryRepository {

    /** false when the refund already opened a recovery for that transfer (business-key de-duplication). */
    boolean insert(TransferRecovery recovery);

    Optional<TransferRecovery> find(UUID id);

    /** SELECT … FOR UPDATE. */
    Optional<TransferRecovery> lock(UUID id);

    /** Optimistic: the version must match; returns the recovery with version + 1. */
    TransferRecovery update(TransferRecovery recovery);

    /** The organisation's open recoveries with something outstanding, oldest first, locked. */
    List<TransferRecovery> lockOutstanding(String orgId, String currency);

    /** Σ outstanding of the organisation's open recoveries: what it owes the platform. */
    long outstanding(String orgId, String currency);

    /** Newest first; {@code status} and {@code orgId} null for all. */
    List<TransferRecovery> list(TransferRecoveryStatus status, String orgId, int limit);

    /** The recoveries a refund opened, oldest first. */
    List<TransferRecovery> ofRefund(UUID refundId);

    void insertDeduction(UUID id, UUID recoveryId, String orderId, Money amount, UUID journalId, Instant at);
}
