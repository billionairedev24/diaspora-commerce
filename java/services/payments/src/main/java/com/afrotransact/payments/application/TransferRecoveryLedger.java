package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.domain.TransferRecovery;
import com.afrotransact.payments.port.StripeGatewayException;

/**
 * The money side of refused transfer reversals: opening what a seller owes the platform when Stripe will not take a
 * refund back from a transfer, and recovering it from the seller's later transfers. Every operation joins the caller's
 * SERIALIZABLE transaction (a refund, a settlement); staff resolution goes through {@link TransferRecoveryService}.
 */
interface TransferRecoveryLedger {

    /**
     * Records that Stripe refused to reverse {@code amount} of {@code transfer} for {@code refundId}: the seller now
     * owes it. Posts TRANSFER_RECOVERY_OPENED (the refund's debit moves from the order's payable to the organisation's
     * unattributed payable, which goes negative). Idempotent per (refund, transfer): a replay returns the first row.
     */
    TransferRecovery open(UUID refundId, SettlementTransfer transfer, Money amount, StripeGatewayException refusal,
                          Instant now, String by);

    /** The recoveries a refund opened, oldest first. */
    List<TransferRecovery> ofRefund(UUID refundId);

    /** What the organisation owes the platform from refused reversals and has not repaid. */
    long outstanding(String orgId, String currency);

    /**
     * Takes up to {@code payable} of the organisation's open recoveries out of the order's payable before it is
     * transferred, oldest first (the transfer is smaller; the platform keeps the difference). Returns what was taken.
     */
    Money recoverFrom(String orderId, String orgId, Money payable, Instant now);
}
