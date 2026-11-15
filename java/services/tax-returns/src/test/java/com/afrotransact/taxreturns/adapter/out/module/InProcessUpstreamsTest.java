package com.afrotransact.taxreturns.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.inventory.api.ReturnsApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.api.OrderLookupApi;
import com.afrotransact.payments.api.ReturnRefundApi;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.ItemCondition;
import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;
import com.afrotransact.taxreturns.port.InventoryGateway;
import com.afrotransact.taxreturns.port.PaymentsGateway;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Tax-returns' in-process adapters call as its principal and give the HTTP adapters' answers: 404 is empty, a refusal keeps its status. */
class InProcessUpstreamsTest {

    private static final CallerPrincipal TAX = new CallerPrincipal("tax-returns", "tax-returns-service",
            Set.of("orders:lookup", "payments:service", "inventory:adjust"));

    private static StaticListableBeanFactory beans(Object api) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("api", api);
        beans.addBean("callers", (CallerPrincipals) module -> TAX);
        return beans;
    }

    @Test
    void anUnknownOrderIsEmptyAndTheSnapshotLeavesOutCancelledShipments() {
        UUID id = UUID.randomUUID();
        UUID shipment = UUID.randomUUID();
        OrderLookupApi api = new OrderLookupApi() {
            @Override
            public OrderView lookup(String idOrNumber) {
                assertThat(InProcessCalls.require()).isEqualTo(TAX);
                if (!id.toString().equals(idOrNumber)) {
                    throw new ModuleApiException(404, "orders.not-found", "none");
                }
                OrderLookupApi.Line line = new OrderLookupApi.Line("l1", 1, "AT00000001", "AT00000000", "Title", "GROCERY",
                        "food", false, 2, 500, 0, 40, 0, 0, null, null, null, null, null, null, null);
                return new OrderView(id.toString(), "N-1", "cust", "DELIVERED", "usd", "pay", Instant.parse("2026-11-01T00:00:00Z"),
                        1000, 0, 80, 0, 1080, new ShipTo("A B", "555", "1 Main", null, "Houston", "TX", "77002", "US"),
                        List.of(new Shipment(shipment.toString(), 1, "org_s", "Seller", "UNKNOWN_TYPE", "CARRIER", null,
                                "delivered", false, 0, 1000, null, null, null, null, Instant.parse("2026-11-03T00:00:00Z"),
                                true, null, List.of(line)), new Shipment(UUID.randomUUID().toString(), 2, "org_s", "Seller",
                                "SELLER_FULFILLED", "CARRIER", null, "cancelled", false, 0, 0, null, null, null, null, null,
                                false, null, List.of())));
            }

            @Override
            public PurchaseProof purchase(String customerId, String productAtin) {
                return null;
            }
        };
        StaticListableBeanFactory beans = beans(api);
        InProcessOrderLookup lookup = new InProcessOrderLookup(beans.getBeanProvider(OrderLookupApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        assertThat(lookup.placedOrder(UUID.randomUUID())).isEmpty();
        assertThat(lookup.placedOrder(id)).hasValueSatisfying(o -> {
            assertThat(o.currency()).isEqualTo("USD");
            assertThat(o.shipments()).singleElement().satisfies(s -> {
                assertThat(s.fulfilment()).isEqualTo(FulfilmentType.SELLER_FULFILLED);
                assertThat(s.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(2));
            });
            assertThat(o.customerAddress().city()).isEqualTo("Houston");
        });
    }

    @Test
    void aMissingPaymentIsEmptyAndARefusedRefundKeepsItsStatusAndCode() {
        ReturnRefundApi api = new ReturnRefundApi() {
            @Override
            public Optional<ChargedPayment> paymentForOrder(UUID orderId) {
                throw new ModuleApiException(404, "payments.not-found", "none");
            }

            @Override
            public IssuedRefund refund(UUID paymentId, List<RefundAmount> lines, String reason, String key) {
                throw new ModuleApiException(409, "payments.already-refunded", "already");
            }
        };
        StaticListableBeanFactory beans = beans(api);
        PaymentsGateway gateway = new InProcessPaymentsGateway(beans.getBeanProvider(ReturnRefundApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        assertThat(gateway.paymentForOrder(UUID.randomUUID())).isEmpty();
        assertThatThrownBy(() -> gateway.refund(UUID.randomUUID(), List.of(new RefundLineAmount("l1", 1, 1L, 0L, 0L, 0L)), null, "k"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("payments.already-refunded");
                    assertThat(e.upstream()).isEqualTo("payments");
                });
    }

    @Test
    void aFailureWithoutAnAnswerIsStatusZeroAndAMissingCalleeStopsTheStart() {
        ReturnsApi api = (orderId, sku, quantity, condition, reason, node, key) -> {
            throw new IllegalStateException("down");
        };
        StaticListableBeanFactory beans = beans(api);
        InventoryGateway inventory = new InProcessInventoryGateway(beans.getBeanProvider(ReturnsApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
        assertThatThrownBy(() -> inventory.acceptReturn("o", "sku", 1, ItemCondition.RESTOCK, "why", null, "k"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.status()).isZero());
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        assertThatThrownBy(() -> new InProcessInventoryGateway(empty.getBeanProvider(ReturnsApi.class),
                empty.getBeanProvider(CallerPrincipals.class), empty)).hasMessageContaining("'tax-returns'")
                .hasMessageContaining("'inventory'").hasMessageContaining("at.taxreturns.inventory.mode");
    }
}
