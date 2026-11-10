package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.taxreturns.domain.returns.DeliveryMethod;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxDecisionStamp;
import com.afrotransact.taxreturns.domain.tax.TaxRateTable;
import com.afrotransact.taxreturns.domain.tax.TaxTransaction;
import com.afrotransact.taxreturns.port.OrderSnapshotRepository;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import com.afrotransact.taxreturns.port.TaxTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Ledger rows carry the stored decision the sale was priced under, and refund rows reverse with the same one. */
class TaxLedgerDecisionTest {

    private final TaxTransactionRepository ledgerRepo = mock(TaxTransactionRepository.class);
    private final TaxRateRepository rates = mock(TaxRateRepository.class);
    private final TaxLedger ledger = new TaxLedger(ledgerRepo, rates, mock(OrderSnapshotRepository.class),
            mock(DomainEventPublisher.class));

    @Test
    void aSaleLineIsStampedWithTheDecisionOrdersSentAndARefundCopiesIt() {
        when(rates.table()).thenReturn(new TaxRateTable(List.of(), List.of()));
        when(ledgerRepo.insertIfAbsent(any())).thenReturn(true);
        UUID orderId = UUID.randomUUID();
        PlacedOrder order = new PlacedOrder(orderId, "AT-1", "cust", "USD",
                new TaxAddress("US", "TX", "77002", "Houston"),
                Instant.parse("2026-11-09T10:00:00Z"), List.of(new PlacedOrder.Shipment(UUID.randomUUID(), "org_a", "A",
                FulfilmentType.SELLER_FULFILLED,
                DeliveryMethod.CARRIER, 1_100, 0, null,
                List.of(new PlacedOrder.Line("l1", "AT1", "x", 1, 1_000, 83, null, false, 0)))), null);

        ledger.recordSale(order, Map.of("l1", new TaxDecisionStamp("TAXABLE", "AT.X.Y", "tx-1", "ZIP")));

        ArgumentCaptor<TaxTransaction> sale = ArgumentCaptor.forClass(TaxTransaction.class);
        verify(ledgerRepo).insertIfAbsent(sale.capture());
        assertThat(sale.getValue().internalTaxCode()).isEqualTo("AT.X.Y");
        assertThat(sale.getValue().outcome()).isEqualTo("TAXABLE");
        assertThat(sale.getValue().ruleVersion()).isEqualTo("tx-1");
        assertThat(sale.getValue().addressPrecision()).isEqualTo("ZIP");

        when(ledgerRepo.sale(orderId, "l1")).thenReturn(Optional.of(sale.getValue()));
        ledger.recordRefund(orderId, "pref-1", "USD", List.of(new ReversalLine(
                "l1", 1_000, 83)), Instant.parse("2026-11-10T10:00:00Z"));
        ArgumentCaptor<TaxTransaction> all = ArgumentCaptor.forClass(TaxTransaction.class);
        verify(ledgerRepo, times(2)).insertIfAbsent(all.capture());
        TaxTransaction refund = all.getAllValues().get(1);
        assertThat(refund.taxMinor()).isEqualTo(-83);
        assertThat(refund.internalTaxCode()).isEqualTo("AT.X.Y");
        assertThat(refund.outcome()).isEqualTo("TAXABLE");
    }
}
