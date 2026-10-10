package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Settlement;
import com.afrotransact.payments.domain.SettlementTransfer;

/** Settlement holds, transfers to connected accounts and their reversals. */
public interface SettlementRepository {

    /** Inserts the hold; false when the order already has one. */
    boolean insert(Settlement settlement);

    Optional<Settlement> find(String orderId);

    /** Locks the next OPEN matured settlement due for a check (FOR UPDATE SKIP LOCKED). */
    Optional<Settlement> lockNextMatured(Instant now);

    /** Marks the settlement SETTLED, or OPEN with the next check time when something could not be transferred. */
    void markChecked(String orderId, boolean settled, Instant nextCheckAt, Instant now);

    /** A capture after settlement re-opens it so the new payable is transferred too. */
    void reopen(String orderId, Instant now);

    void insertTransfer(SettlementTransfer transfer);

    void updateTransfer(SettlementTransfer transfer);

    List<SettlementTransfer> transfers(String orderId, String orgId);

    List<SettlementTransfer> lockTransfers(String orderId, String orgId);

    Optional<SettlementTransfer> lockTransferByStripeId(String stripeTransferId);

    boolean reversalExists(String stripeReversalId);

    void insertReversal(UUID transferId, String stripeReversalId, Money amount, UUID refundId, Instant at);

    /** Σ (transferred − reversed) to the organisation. */
    Money transferredTo(String orgId, String currency);
}
