package com.afrotransact.taxreturns.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.taxreturns.application.OrderIntakeService;
import com.afrotransact.taxreturns.application.RequestReturnCommand;
import com.afrotransact.taxreturns.application.ReturnEligibility;
import com.afrotransact.taxreturns.application.ReturnService;
import com.afrotransact.taxreturns.application.ReturnsJobService;
import com.afrotransact.taxreturns.application.TaxLedgerService;
import com.afrotransact.taxreturns.domain.returns.DeliveryMethod;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.returns.RefundBreakdown;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.returns.ReturnLineRequest;
import com.afrotransact.taxreturns.domain.returns.ReturnMethod;
import com.afrotransact.taxreturns.domain.returns.ReturnReason;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaStatus;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxTransactionKind;
import com.afrotransact.taxreturns.port.PaymentsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * S235 / S229: returns use the order's own data (event or orders lookup) instead of customer-entered addresses,
 * respect what payments already refunded per line, post stock to the return centre and schedule driver pickups with
 * fulfilment.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "at.taxreturns.jobs.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Testcontainers
@Import(StubUpstreams.class)
class ReturnsIntegrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final ReturnAddress ORDER_ADDRESS = new ReturnAddress("Adaeze Nwosu", "12 Bayou Ln", null,
            "Houston", "TX", "77019", "US", null);

    private final OrderIntakeService intake;
    private final ReturnService returns;
    private final ReturnsJobService jobs;
    private final TaxLedgerService ledger;
    private final TestActors actors;
    private final StubUpstreams.FakePayments payments;
    private final StubUpstreams.FakeCarrier carrier;
    private final StubUpstreams.FakeOrders orders;
    private final StubUpstreams.FakePickups pickups;

    private Orders order;

    @Autowired
    ReturnsIntegrationIT(OrderIntakeService intake, ReturnService returns, ReturnsJobService jobs,
                         TaxLedgerService ledger, TestActors actors, StubUpstreams.FakePayments payments,
                         StubUpstreams.FakeCarrier carrier, StubUpstreams.FakeOrders orders,
                         StubUpstreams.FakePickups pickups) {
        this.intake = intake;
        this.returns = returns;
        this.jobs = jobs;
        this.ledger = ledger;
        this.actors = actors;
        this.payments = payments;
        this.carrier = carrier;
        this.orders = orders;
        this.pickups = pickups;
    }

    @BeforeEach
    void placeAndDeliver() {
        actors.staff();
        order = new Orders("cust_" + UUID.randomUUID());
        Instant placed = Instant.now().minus(Duration.ofDays(5));
        intake.onOrderPlaced(UUID.randomUUID(), order.placed(placed));
        intake.onShipmentDelivered(UUID.randomUUID(), order.s1, placed.plus(Duration.ofDays(2)));
        intake.onShipmentDelivered(UUID.randomUUID(), order.s2, placed.plus(Duration.ofDays(3)));
        payments.payments.put(order.orderId, order.payment());
    }

    @Test
    void dropOffLabelsShipFromTheOrdersAddressWithoutAskingTheCustomer() {
        actors.customer(order.customer);
        Rma rma = returns.request(new RequestReturnCommand(order.orderId, order.s1, ReturnMethod.DROP_OFF,
                List.of(new ReturnLineRequest(order.dress, 1, ReturnReason.DAMAGED, "Torn", List.of("a1"))), null),
                "key-no-address");
        assertThat(rma.status()).isEqualTo(RmaStatus.LABEL_ISSUED);
        assertThat(rma.returnFrom()).isEqualTo(ORDER_ADDRESS);
        assertThat(carrier.requests).anySatisfy(r -> {
            assertThat(r.reference()).isEqualTo(rma.number());
            assertThat(r.from()).isEqualTo(ORDER_ADDRESS);
        });
    }

    @Test
    void ordersPlacedBeforeThisServiceAreBackfilledFromTheOrdersLookup() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        String customer = "cust_" + UUID.randomUUID();
        String line = UUID.randomUUID().toString();
        orders.orders.put(orderId, new PlacedOrder(orderId, "AT-2026-OLD" + orderId.toString().substring(0, 3),
                customer, "USD", new TaxAddress("US", "TX", "77019", "Houston"),
                Instant.now().minus(Duration.ofDays(9)), List.of(new PlacedOrder.Shipment(shipmentId, "org_a",
                "Lagos Textile Hub", FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER, 7_035, 0,
                Instant.now().minus(Duration.ofDays(6)), List.of(new PlacedOrder.Line(line, "ATDRESS001",
                "Ankara wrap dress", 1, 6_499, 536, "dresses", false)))), ORDER_ADDRESS));
        actors.customer(customer);
        ReturnEligibility e = returns.eligibility(orderId);
        assertThat(e.shipments()).singleElement().satisfies(s -> assertThat(s.deliveredAt()).isNotNull());
        assertThat(e.shipments().getFirst().lines().getFirst().eligibility().methods())
                .contains(ReturnMethod.DROP_OFF);
        actors.staff();
        assertThat(ledger.forOrder(orderId)).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TaxTransactionKind.SALE);
            assertThat(t.taxMinor()).isEqualTo(536);
        });
    }

    @Test
    void refundsLeaveOutWhatPaymentsAlreadyRefundedOnTheLine() {
        PaymentsGateway.ChargedPayment p = order.payment();
        // an A-to-z claim already refunded one dress (items 64.99, tax 5.36) through payments
        payments.payments.put(order.orderId, new PaymentsGateway.ChargedPayment(p.paymentId(), "USD", List.of(
                new PaymentsGateway.ChargedLine(order.dress, "org_a", 2, 12_998, 799, 1_072, true, 6_499, 0, 536),
                p.lines().get(1), p.lines().get(2))));
        actors.customer(order.customer);
        Rma rma = returns.request(new RequestReturnCommand(order.orderId, order.s1, ReturnMethod.DROP_OFF,
                List.of(new ReturnLineRequest(order.dress, 2, ReturnReason.NO_LONGER_NEEDED, null, null)), null),
                "key-after-claim");
        actors.staff();
        RefundBreakdown quote = returns.refundQuote(rma.id());
        assertThat(quote.lines()).singleElement().satisfies(l -> {
            assertThat(l.itemsMinor()).isEqualTo(12_998 - 6_499);
            assertThat(l.taxMinor()).isEqualTo(1_072 - 536);
        });
    }

    @Test
    void driverPickupsAreScheduledWithFulfilmentAndCancelledWithTheReturn() {
        actors.customer(order.customer);
        pickups.failNext = true;
        Rma rma = returns.request(new RequestReturnCommand(order.orderId, order.s2, ReturnMethod.DRIVER_PICKUP,
                List.of(new ReturnLineRequest(order.pillow, 1, ReturnReason.DAMAGED, "Ripped", List.of("a1"))), null),
                "key-pickup");
        actors.seller("org_b", "returns:manage");
        Rma approved = returns.approve(rma.id(), "OK");
        assertThat(approved.status()).isEqualTo(RmaStatus.LABEL_ISSUED);
        assertThat(pickups.scheduled).doesNotContainKey(rma.id());

        jobs.runOnce();
        assertThat(pickups.scheduled).containsKey(rma.id());
        assertThat(pickups.scheduled.get(rma.id())).satisfies(r -> {
            assertThat(r.address()).isEqualTo(ORDER_ADDRESS);
            assertThat(r.rmaNumber()).isEqualTo(rma.number());
            assertThat(r.units()).isEqualTo(1);
            assertThat(r.depotNodeId()).as("seller-fulfilled: no return centre").isNull();
            assertThat(r.collectBy()).isNotNull();
        });
        jobs.runOnce();
        assertThat(pickups.scheduled).hasSize(pickups.scheduled.size());

        actors.customer(order.customer);
        assertThat(returns.cancel(rma.id()).status()).isEqualTo(RmaStatus.CANCELLED);
        assertThat(pickups.cancelled).contains("rp-" + rma.id());
    }
}
