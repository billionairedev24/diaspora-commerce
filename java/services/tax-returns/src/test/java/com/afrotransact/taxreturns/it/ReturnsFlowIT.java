package com.afrotransact.taxreturns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.taxreturns.application.AdjustmentLine;
import com.afrotransact.taxreturns.application.CarrierScan;
import com.afrotransact.taxreturns.application.InspectCommand;
import com.afrotransact.taxreturns.application.OrderIntakeService;
import com.afrotransact.taxreturns.application.RefundService;
import com.afrotransact.taxreturns.application.RequestReturnCommand;
import com.afrotransact.taxreturns.application.ReturnEligibility;
import com.afrotransact.taxreturns.application.ReturnPolicyService;
import com.afrotransact.taxreturns.application.ReturnSearch;
import com.afrotransact.taxreturns.application.ReturnService;
import com.afrotransact.taxreturns.application.ReturnsJobService;
import com.afrotransact.taxreturns.application.TaxLedgerService;
import com.afrotransact.taxreturns.domain.returns.InspectionOutcome;
import com.afrotransact.taxreturns.domain.returns.ItemCondition;
import com.afrotransact.taxreturns.domain.returns.Refund;
import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;
import com.afrotransact.taxreturns.domain.returns.RefundState;
import com.afrotransact.taxreturns.domain.returns.RefundStatus;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.returns.ReturnLineRequest;
import com.afrotransact.taxreturns.domain.returns.ReturnMethod;
import com.afrotransact.taxreturns.domain.returns.ReturnReason;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaStatus;
import com.afrotransact.taxreturns.domain.returns.SellerReturnPolicy;
import com.afrotransact.taxreturns.domain.returns.StockStatus;
import com.afrotransact.taxreturns.domain.tax.TaxTransaction;
import com.afrotransact.taxreturns.domain.tax.TaxTransactionKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** S114 / S115 end to end against PostgreSQL, with in-memory payments, inventory, carrier-gateway and catalog. */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "at.taxreturns.jobs.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Testcontainers
@Import(StubUpstreams.class)
class ReturnsFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final ReturnAddress HOME = new ReturnAddress("Adaeze Nwosu", "12 Bayou Ln", null, "Houston", "TX",
            "77019", "US", "+17135550100");
    private static final ReturnAddress SELLER = new ReturnAddress("Bamako Home", "400 Market St", null, "Atlanta",
            "GA", "30303", "US", null);

    private final OrderIntakeService intake;
    private final ReturnService returns;
    private final RefundService refunds;
    private final ReturnPolicyService policies;
    private final ReturnsJobService jobs;
    private final TaxLedgerService ledger;
    private final TestActors actors;
    private final StubUpstreams.FakePayments payments;
    private final StubUpstreams.FakeInventory inventory;
    private final StubUpstreams.FakeCarrier carrier;
    private final JdbcClient jdbc;

    private Orders order;

    @Autowired
    ReturnsFlowIT(OrderIntakeService intake, ReturnService returns, RefundService refunds,
                  ReturnPolicyService policies, ReturnsJobService jobs, TaxLedgerService ledger, TestActors actors,
                  StubUpstreams.FakePayments payments, StubUpstreams.FakeInventory inventory,
                  StubUpstreams.FakeCarrier carrier, JdbcClient jdbc) {
        this.intake = intake;
        this.returns = returns;
        this.refunds = refunds;
        this.policies = policies;
        this.jobs = jobs;
        this.ledger = ledger;
        this.actors = actors;
        this.payments = payments;
        this.inventory = inventory;
        this.carrier = carrier;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void placeAndDeliver() {
        actors.staff();
        order = new Orders("cust_" + UUID.randomUUID());
        Instant placed = Instant.now().minus(Duration.ofDays(5));
        UUID eventId = UUID.randomUUID();
        assertThat(intake.onOrderPlaced(eventId, order.placed(placed))).isTrue();
        assertThat(intake.onOrderPlaced(eventId, order.placed(placed))).as("redelivery").isFalse();
        assertThat(intake.onShipmentDelivered(UUID.randomUUID(), order.s1, placed.plus(Duration.ofDays(2)))).isTrue();
        assertThat(intake.onShipmentDelivered(UUID.randomUUID(), order.s2, placed.plus(Duration.ofDays(3)))).isTrue();
        payments.payments.put(order.orderId, order.payment());
    }

    @Test
    void eligibilityPerShipmentAndLine() {
        actors.customer(order.customer);
        ReturnEligibility e = returns.eligibility(order.orderId);
        ReturnEligibility.Shipment s1 = e.shipments().getFirst();
        assertThat(s1.lines()).extracting(l -> l.eligibility().reportProblemOnly()).containsExactly(false, true);
        assertThat(s1.lines().getFirst().eligibility().methods()).containsExactly(ReturnMethod.DROP_OFF);
        assertThat(s1.lines().getFirst().eligibility().maxQuantity()).isEqualTo(2);
        assertThat(e.shipments().get(1).lines().getFirst().eligibility().methods())
                .containsExactlyInAnyOrder(ReturnMethod.DROP_OFF, ReturnMethod.DRIVER_PICKUP);

        actors.customer("someone_else");
        assertThatThrownBy(() -> returns.eligibility(order.orderId)).isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void dropOffRefundAtFirstScanThenInspectionRestocks() {
        actors.customer(order.customer);
        RequestReturnCommand cmd = new RequestReturnCommand(order.orderId, order.s1, ReturnMethod.DROP_OFF,
                List.of(new ReturnLineRequest(order.dress, 1, ReturnReason.DAMAGED, "Seam torn", List.of("asset_1"))),
                HOME);
        Rma rma = returns.request(cmd, "key-dress");
        assertThat(rma.number()).matches("RMA-[0-9A-Z]{8}");
        assertThat(rma.status()).isEqualTo(RmaStatus.LABEL_ISSUED);
        assertThat(rma.autoApproved()).isTrue();
        // items 64.99 + tax 5.36 + shipping 7.99 × 64.99 / 148.48 = 3.50 (seller fault)
        assertThat(rma.estimateMinor()).isEqualTo(6_499 + 536 + 350);
        assertThat(rma.refundAtFirstScan()).isTrue();
        assertThat(rma.label().trackingNumber()).startsWith("1ZRET");
        assertThat(rma.qrPayload()).isEqualTo("AFROTRANSACT-RETURN:" + rma.number() + ":" + rma.label()
                .trackingNumber());
        assertThat(rma.dropOffBy()).isAfter(Instant.now().plus(Duration.ofDays(13)));
        assertThat(returns.request(cmd, "key-dress").id()).as("idempotent replay").isEqualTo(rma.id());
        assertThat(carrier.requests).anySatisfy(r -> {
            assertThat(r.reference()).isEqualTo(rma.number());
            assertThat(r.from()).isEqualTo(HOME);
            assertThat(r.to().city()).isEqualTo("Houston");
        });

        Rma moving = scan(rma, "in_transit");
        assertThat(moving.status()).isEqualTo(RmaStatus.IN_TRANSIT);
        assertThat(moving.refundState()).isEqualTo(RefundState.ISSUED);
        assertThatThrownBy(() -> returns.cancel(rma.id())).isInstanceOf(ConflictException.class);
        StubUpstreams.FakePayments.Call call = payments.refunds.stream()
                .filter(c -> c.reason().contains(rma.number())).findFirst().orElseThrow();
        assertThat(call.lines()).containsExactly(new RefundLineAmount(order.dress, 1, 6_499, 350, 536, 0));

        assertThat(scan(rma, "delivered").status()).isEqualTo(RmaStatus.RECEIVED);
        actors.staff();
        Rma done = returns.inspect(rma.id(), new InspectCommand(List.of(new InspectCommand.Line(order.dress,
                InspectionOutcome.PASS, ItemCondition.RESTOCK, "Resealable")), null));
        assertThat(done.status()).isEqualTo(RmaStatus.REFUNDED);
        assertThat(done.lines().getFirst().stockStatus()).isEqualTo(StockStatus.POSTED);
        assertThat(inventory.postings).contains("ATDRESS001:1:RESTOCK:rma-" + rma.id() + "-" + order.dress);
        assertThat(payments.refunds.stream().filter(c -> c.reason().contains(rma.number()))).hasSize(1);

        assertThat(eventTypes(rma.id())).containsExactlyInAnyOrder("returns.rma.requested", "returns.rma.approved",
                "returns.rma.label_issued", "returns.rma.in_transit", "returns.rma.received",
                "returns.rma.inspected", "returns.rma.refunded");
        List<TaxTransaction> tax = ledger.forOrder(order.orderId);
        assertThat(tax).filteredOn(t -> t.kind() == TaxTransactionKind.REFUND).singleElement()
                .satisfies(t -> {
                    assertThat(t.taxMinor()).isEqualTo(-536);
                    assertThat(t.jurisdiction()).isEqualTo("US-TX");
                    assertThat(t.sellerOrgId()).isEqualTo("org_a");
                });
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'returns.refund.issued'")
                .query(Long.class).single()).isPositive();
    }

    @Test
    void perishableProblemReportIsRefundedWithoutReturnAfterStaffApproval() {
        actors.customer(order.customer);
        assertThatThrownBy(() -> returns.request(new RequestReturnCommand(order.orderId, order.s1,
                ReturnMethod.DROP_OFF, List.of(new ReturnLineRequest(order.jollof, 1, ReturnReason.NO_LONGER_NEEDED,
                null, null)), HOME), "key-food-bad"))
                .isInstanceOfSatisfying(ReturnRuleViolation.class, ex -> assertThat(ex.fields())
                        .containsExactlyInAnyOrder("lines[0].reason", "method"));

        Rma rma = returns.request(new RequestReturnCommand(order.orderId, order.s1, ReturnMethod.NO_RETURN,
                List.of(new ReturnLineRequest(order.jollof, 1, ReturnReason.EXPIRED_OR_SPOILED, "Mouldy",
                        List.of("asset_2"))), null), "key-food");
        assertThat(rma.status()).as("above the low-value threshold: staff review").isEqualTo(RmaStatus.REQUESTED);
        assertThat(rma.approvalDueAt()).isNull();

        actors.seller("org_a", "returns:manage");
        assertThatThrownBy(() -> returns.approve(rma.id(), null)).isInstanceOf(ForbiddenActionException.class);
        actors.staff();
        Rma refunded = returns.approve(rma.id(), "Photo shows spoilage");
        assertThat(refunded.status()).isEqualTo(RmaStatus.REFUNDED);
        assertThat(refunded.refundState()).isEqualTo(RefundState.ISSUED);
        Refund refund = refunds.forRma(rma.id()).getFirst();
        // grocery: no tax; seller fault: 7.99 × 18.50 / 148.48 = 1.00 of shipping, carried by the dress line
        assertThat(refund.lines()).containsExactlyInAnyOrder(new RefundLineAmount(order.dress, 0, 0, 100, 0, 0),
                new RefundLineAmount(order.jollof, 1, 1_850, 0, 0, 0));
        assertThat(inventory.postings).noneMatch(p -> p.startsWith("ATJOLLOF01"));
    }

    @Test
    void sellerFulfilledDriverPickupWithSellerDecisionAndFailedInspection() {
        actors.customer(order.customer);
        RequestReturnCommand pickup = new RequestReturnCommand(order.orderId, order.s2, ReturnMethod.DRIVER_PICKUP,
                List.of(new ReturnLineRequest(order.pillow, 1, ReturnReason.NO_LONGER_NEEDED, null, null)), null);
        Rma first = returns.request(pickup, "key-pillow-1");
        assertThat(first.status()).isEqualTo(RmaStatus.REQUESTED);
        assertThat(first.approvalDueAt()).isAfter(Instant.now().plus(Duration.ofHours(47)));
        assertThat(returns.cancel(first.id()).status()).isEqualTo(RmaStatus.CANCELLED);

        Rma rma = returns.request(pickup, "key-pillow-2");
        actors.seller("org_x", "returns:manage");
        assertThatThrownBy(() -> returns.approve(rma.id(), null)).isInstanceOf(ForbiddenActionException.class);
        actors.seller("org_b", "returns:manage");
        assertThat(returns.search(new ReturnSearch(null, null, RmaStatus.REQUESTED, 10)))
                .extracting(Rma::id).contains(rma.id());
        Rma approved = returns.approve(rma.id(), "Fine");
        assertThat(approved.status()).isEqualTo(RmaStatus.LABEL_ISSUED);
        assertThat(approved.handoverCode()).matches("\\d{6}");

        actors.driver();
        assertThatThrownBy(() -> returns.pickedUp(rma.id(), "000000".equals(approved.handoverCode()) ? "111111"
                : "000000")).isInstanceOf(ReturnRuleViolation.class);
        assertThat(returns.pickedUp(rma.id(), approved.handoverCode()).status()).isEqualTo(RmaStatus.IN_TRANSIT);

        actors.seller("org_b", "returns:manage");
        assertThat(returns.receive(rma.id()).status()).isEqualTo(RmaStatus.RECEIVED);
        Rma rejected = returns.inspect(rma.id(), new InspectCommand(List.of(new InspectCommand.Line(order.pillow,
                InspectionOutcome.FAIL, null, "Stained")), "Item used"));
        assertThat(rejected.status()).isEqualTo(RmaStatus.REJECTED);
        assertThat(rejected.lines().getFirst().condition()).isEqualTo(ItemCondition.DAMAGED);
        assertThat(rejected.lines().getFirst().stockStatus()).isEqualTo(StockStatus.NOT_TRACKED);
        actors.staff();
        assertThat(refunds.forOrder(order.orderId)).isEmpty();
    }

    @Test
    void sellerApprovalSlaAutoApprovesAndLabelsToTheSellerAddress() {
        actors.staff();
        policies.put(new SellerReturnPolicy("org_b", 30, 1_000, true, false, SELLER, -1L));
        actors.customer(order.customer);
        Rma rma = returns.request(new RequestReturnCommand(order.orderId, order.s2, ReturnMethod.DROP_OFF,
                List.of(new ReturnLineRequest(order.pillow, 1, ReturnReason.ORDERED_BY_MISTAKE, null, null)), HOME),
                "key-sla");
        assertThat(rma.status()).isEqualTo(RmaStatus.REQUESTED);
        assertThat(rma.estimateMinor()).as("10 % restocking fee on a no-fault return").isEqualTo(3_200 - 320 + 264);

        jdbc.sql("update rma set approval_due_at = now() - interval '1 minute' where id = :id").param("id", rma.id())
                .update();
        assertThat(jobs.runOnce().autoApproved()).isEqualTo(1);
        actors.staff();
        Rma labelled = returns.get(rma.id());
        assertThat(labelled.status()).isEqualTo(RmaStatus.LABEL_ISSUED);
        assertThat(labelled.autoApproved()).isTrue();
        assertThat(carrier.requests).anySatisfy(r -> {
            assertThat(r.reference()).isEqualTo(rma.number());
            assertThat(r.originNode()).isEqualTo("org_b");
            assertThat(r.to()).isEqualTo(SELLER);
        });
    }

    @Test
    void partialRefundWithoutReturnRetriesWithTheSameKey() {
        actors.staff();
        payments.failNextWithStatus = 503;
        Refund pending = refunds.adjust(order.orderId, List.of(new AdjustmentLine(order.dress, 1_000, 0)),
                "Goodwill: late delivery", "adj-1");
        assertThat(pending.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(pending.lines().getFirst().taxMinor()).isEqualTo(82); // 10.72 × 10.00 / 129.98 = 0.8247
        Refund issued = refunds.retry(pending.id());
        assertThat(issued.status()).isEqualTo(RefundStatus.ISSUED);
        assertThat(issued.attempts()).isEqualTo(2);
        assertThat(refunds.adjust(order.orderId, List.of(new AdjustmentLine(order.dress, 1_000, 0)),
                "Goodwill: late delivery", "adj-1").id()).as("same key, same refund").isEqualTo(pending.id());
        assertThat(payments.refunds).filteredOn(c -> c.idempotencyKey().equals(pending.id().toString())).hasSize(1);

        payments.failNextWithStatus = 422;
        Refund refused = refunds.adjust(order.orderId, List.of(new AdjustmentLine(order.pillow, 500, 0)),
                "Goodwill", "adj-2");
        assertThat(refused.status()).isEqualTo(RefundStatus.FAILED);

        assertThatThrownBy(() -> refunds.adjust(order.orderId, List.of(new AdjustmentLine(order.dress, 13_000, 0)),
                "Too much", "adj-3")).isInstanceOf(ReturnRuleViolation.class);
        actors.seller("org_b", "refunds:issue");
        assertThatThrownBy(() -> refunds.adjust(order.orderId, List.of(new AdjustmentLine(order.dress, 100, 0)),
                "Not my line", "adj-4")).isInstanceOf(ForbiddenActionException.class);
    }

    private Rma scan(Rma rma, String state) {
        returns.onCarrierScan(UUID.randomUUID(), new CarrierScan("ups", rma.label().trackingNumber(), rma.number(),
                state, Instant.now()));
        actors.staff();
        Rma current = returns.get(rma.id());
        actors.customer(order.customer);
        return current;
    }

    private List<String> eventTypes(UUID rmaId) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at, type")
                .param("id", rmaId.toString()).query(String.class).list();
    }
}
