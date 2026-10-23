package com.afrotransact.taxreturns.port;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.tax.LiabilityRow;
import com.afrotransact.taxreturns.domain.tax.TaxTransaction;
import com.afrotransact.taxreturns.domain.tax.TaxTransactionKind;

/** The tax ledger. */
public interface TaxTransactionRepository {

    /** Inserts unless (kind, sourceRef, lineId) exists; true when inserted. */
    boolean insertIfAbsent(TaxTransaction entry);

    Optional<TaxTransaction> find(TaxTransactionKind kind, String sourceRef, String lineId);

    /** The sale entry of an order line. */
    Optional<TaxTransaction> sale(UUID orderId, String lineId);

    /** Voids the refund entries of a payments refund that failed; returns rows voided. */
    int voidRefund(String sourceRef);

    List<TaxTransaction> byOrder(UUID orderId);

    /** Per jurisdiction and currency for the month; {@code sellerOrgId} null for the whole marketplace. */
    List<LiabilityRow> liability(YearMonth month, String sellerOrgId);

    /** Gross sales (net of refunds) and orders per jurisdiction over the {@code months} months ending {@code through}. */
    List<SalesInto> salesInto(YearMonth through, int months, String currency);

    /** @param grossMinor sales shipped to the jurisdiction, net of refunds */
    record SalesInto(String jurisdiction, long grossMinor, long orders) {
    }
}
