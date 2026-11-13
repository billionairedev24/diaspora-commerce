package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.taxreturns.domain.returns.DeliveryMethod;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.returns.ReturnMethod;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.returns.ReturnPolicy;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.SellerReturnPolicy;
import com.afrotransact.taxreturns.port.OrderSnapshotRepository;
import com.afrotransact.taxreturns.port.RefundRepository;
import com.afrotransact.taxreturns.port.RmaRepository;
import com.afrotransact.taxreturns.port.SellerPolicyRepository;
import com.afrotransact.taxreturns.port.TaxTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Cross-tenant tests for returns, refunds, return policies and seller tax statements: the real application
 * services and RBAC checkers (production wiring, no OpenFGA) over mocked ports. Named by
 * tenant-isolation-matrix.txt.
 */
class TaxReturnsTenantIsolationTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final String SELLER_PERMS_1 = TaxReturnsPermissions.RETURNS_MANAGE;
    private static final String SELLER_PERMS_2 = TaxReturnsPermissions.REFUNDS_ISSUE;
    private static final String SELLER_PERMS_3 = TaxReturnsPermissions.PAYOUTS_READ;

    private final TenantFixture fx = new TenantFixture();
    private final OrderSnapshotRepository orders = mock(OrderSnapshotRepository.class);
    private final RmaRepository rmas = mock(RmaRepository.class);
    private final SellerPolicyRepository policies = mock(SellerPolicyRepository.class);
    private final RefundRepository refundRepo = mock(RefundRepository.class);
    private final TaxTransactionRepository transactions = mock(TaxTransactionRepository.class);
    private final ReturnWorkflow workflow = mock(ReturnWorkflow.class);
    private final RefundOrchestrator orchestrator = mock(RefundOrchestrator.class);
    private final OrderIntakeService intake = mock(OrderIntakeService.class);

    private ReturnService returns;
    private RefundService refunds;
    private ReturnPolicyService policyService;
    private TaxLedgerService ledger;

    private Rma rmaOfB;
    private PlacedOrder orderOfB;

    @BeforeEach
    void wire() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Access access = new Access(fx.resolver(), fx.permissions());
        TransactionOperations tx = TransactionOperations.withoutTransaction();
        returns = new ReturnServiceImpl(orders, rmas, policies, mock(ReturnPolicy.class), orchestrator, workflow,
                mock(RmaEvents.class), mock(InboxDeduplicator.class), access, tx, clock, intake, new HandoverAttemptsImpl(clock));
        refunds = new RefundServiceImpl(orchestrator, refundRepo, orders, access, tx);
        policyService = new ReturnPolicyServiceImpl(policies, access);
        ledger = new TaxLedgerServiceImpl(transactions, mock(TaxLedger.class), mock(InboxDeduplicator.class), access,
                clock);

        PlacedOrder.Line line = new PlacedOrder.Line("line-b", "ATIN1", "Thing", 1, 1000, 80, "goods", false);
        PlacedOrder.Shipment shipment = new PlacedOrder.Shipment(UUID.randomUUID(), TenantFixture.ORG_B, "B Store",
                FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, 1000, 0, NOW, List.of(line));
        orderOfB = new PlacedOrder(UUID.randomUUID(), "ORD-B", TenantFixture.CUSTOMER_2, "USD", null, NOW,
                List.of(shipment));
        rmaOfB = Rma.requested(UUID.randomUUID(), "RMA-B1", orderOfB.orderId(), "ORD-B", shipment.shipmentId(),
                TenantFixture.CUSTOMER_2, TenantFixture.ORG_B, FulfilmentType.SELLER_FULFILLED,
                DeliveryMethod.CARRIER, ReturnMethod.DROP_OFF, List.of(), "USD", 1000, null, false, null, NOW);

        when(workflow.load(rmaOfB.id())).thenReturn(rmaOfB);
        when(rmas.findById(rmaOfB.id())).thenReturn(Optional.of(rmaOfB));
        when(rmas.findByNumber("RMA-B1")).thenReturn(Optional.of(rmaOfB));
        when(orders.find(orderOfB.orderId())).thenReturn(Optional.of(orderOfB));
        when(intake.order(orderOfB.orderId())).thenReturn(Optional.of(orderOfB));
    }

    private Actor sellerA() {
        return TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_1, SELLER_PERMS_2, SELLER_PERMS_3);
    }

    @Test
    void positiveControlOwnersReachTheirReturn() {
        fx.as(TenantFixture.customer(TenantFixture.CUSTOMER_2));
        assertThat(returns.get(rmaOfB.id())).isSameAs(rmaOfB);
        fx.as(TenantFixture.sellerOf(TenantFixture.ORG_B, SELLER_PERMS_1));
        assertThat(returns.getByNumber("RMA-B1")).isSameAs(rmaOfB);
    }

    @Test
    void foreignSellerAndForeignCustomerCannotReadOrDriveAnotherTenantsReturn() {
        UUID id = rmaOfB.id();
        for (Actor foreign : List.of(sellerA(), TenantFixture.customer(TenantFixture.CUSTOMER_1,
                SELLER_PERMS_1))) {
            fx.as(foreign);
            CrossTenant.assertDenied(() -> returns.get(id));
            CrossTenant.assertDenied(() -> returns.getByNumber("RMA-B1"));
            CrossTenant.assertDenied(() -> returns.refundQuote(id));
            CrossTenant.assertDenied(() -> returns.cancel(id));
            CrossTenant.assertDenied(() -> returns.approve(id, null));
            CrossTenant.assertDenied(() -> returns.reject(id, "no"));
            CrossTenant.assertDenied(() -> returns.issueLabel(id));
            CrossTenant.assertDenied(() -> returns.receive(id));
            CrossTenant.assertDenied(() -> returns.eligibility(orderOfB.orderId()));
        }
        verify(rmas, never()).update(any());
        verifyNoInteractions(refundRepo);
    }

    @Test
    void customerCannotDecideOrReceiveTheirOwnReturn() {
        fx.as(TenantFixture.customer(TenantFixture.CUSTOMER_2, SELLER_PERMS_1));
        CrossTenant.assertDenied(() -> returns.approve(rmaOfB.id(), null));
        CrossTenant.assertDenied(() -> returns.receive(rmaOfB.id()));
        CrossTenant.assertDenied(() -> returns.issueLabel(rmaOfB.id()));
    }

    @Test
    void returnListsAreFilteredByTheActorNotByTheClientSuppliedOrg() {
        when(rmas.search(any())).thenReturn(List.of());
        fx.as(sellerA());
        returns.search(new ReturnSearch(TenantFixture.ORG_B, null, null, 50));
        verify(rmas).search(new RmaRepository.Filter(null, TenantFixture.ORG_A, null, null, 50));
        verify(rmas, never()).search(new RmaRepository.Filter(null, TenantFixture.ORG_B, null, null, 50));

        fx.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        returns.search(new ReturnSearch(TenantFixture.ORG_B, null, null, 50));
        verify(rmas).search(new RmaRepository.Filter(TenantFixture.CUSTOMER_1, null, null, null, 50));

        fx.as(new Actor("seller-x", PrincipalType.SELLER_MEMBER, Optional.empty(), Set.of("seller.owner"),
                Set.of(SELLER_PERMS_1)));
        CrossTenant.assertDenied(() -> returns.search(new ReturnSearch(TenantFixture.ORG_B, null, null, 50)));
    }

    @Test
    void returnPolicyIsPerOrganisationAndTheBodyCannotRedirectIt() {
        fx.as(sellerA());
        CrossTenant.assertDenied(() -> policyService.get(TenantFixture.ORG_B));
        CrossTenant.assertDenied(() -> policyService.put(new SellerReturnPolicy(TenantFixture.ORG_B, 30, 0, true,
                false, null, 0)));
        verify(policies, never()).upsert(any());
        when(policies.find(TenantFixture.ORG_A)).thenReturn(Optional.empty());
        assertThat(policyService.get(TenantFixture.ORG_A).orgId()).isEqualTo(TenantFixture.ORG_A);
    }

    @Test
    void refundOnAnotherSellersLinesIsRefused() {
        fx.as(sellerA());
        CrossTenant.assertDenied(() -> refunds.adjust(orderOfB.orderId(),
                List.of(new AdjustmentLine("line-b", 100, 0)), "because", "key-1"));
        verifyNoInteractions(orchestrator);
        CrossTenant.assertDenied(() -> refunds.get(UUID.randomUUID()));
        CrossTenant.assertDenied(() -> refunds.forOrder(orderOfB.orderId()));
        CrossTenant.assertDenied(() -> refunds.forRma(rmaOfB.id()));
        CrossTenant.assertDenied(() -> refunds.retry(UUID.randomUUID()));
        verifyNoInteractions(refundRepo);
    }

    @Test
    void sellerStatementIsForTheActorsOrganisationOnly() {
        fx.as(sellerA());
        CrossTenant.assertDenied(() -> ledger.sellerStatement(TenantFixture.ORG_B, YearMonth.of(2026, 10)));
        verify(transactions, never()).liability(any(), any());
        CrossTenant.assertDenied(() -> ledger.liability(YearMonth.of(2026, 10)));
        CrossTenant.assertDenied(() -> ledger.forOrder(orderOfB.orderId()));
        when(transactions.liability(YearMonth.of(2026, 10), TenantFixture.ORG_A)).thenReturn(List.of());
        assertThat(ledger.sellerStatement(TenantFixture.ORG_A, YearMonth.of(2026, 10)).sellerOrgId())
                .isEqualTo(TenantFixture.ORG_A);
    }

    // ------------------------------------------------------------- POST /v1/returns/{id}/pickup

    private static Actor driver(String subject) {
        return new Actor(subject, PrincipalType.DRIVER, Optional.empty(), Set.of("platform.driver"),
                Set.of("delivery:operate"));
    }

    @Test
    void aDriverCollectsOnlyTheReturnFulfilmentPutOnTheirRoute() {
        fx.as(driver("drv-1"));
        when(workflow.assignedPickupDriver(rmaOfB.id())).thenReturn(Optional.of("drv-2"));
        CrossTenant.assertDenied(() -> returns.pickedUp(rmaOfB.id(), "123456"));
        // not on any route yet, or fulfilment cannot say: nobody is accepted
        when(workflow.assignedPickupDriver(rmaOfB.id())).thenReturn(Optional.empty());
        CrossTenant.assertDenied(() -> returns.pickedUp(rmaOfB.id(), "123456"));
    }

    @Test
    void wrongHandoverCodesAreLimitedPerReturnWhoeverTries() {
        when(workflow.assignedPickupDriver(rmaOfB.id())).thenReturn(Optional.of("drv-1"));
        fx.as(driver("drv-1"));
        for (int i = 0; i < HandoverAttemptsImpl.MAX_FAILURES; i++) {
            assertThatThrownBy(() -> returns.pickedUp(rmaOfB.id(), "000000")).isInstanceOf(ReturnRuleViolation.class);
        }
        // locked now, even for the right driver; and a second driver account shares the same budget
        CrossTenant.assertDenied(() -> returns.pickedUp(rmaOfB.id(), "000000"));
        when(workflow.assignedPickupDriver(rmaOfB.id())).thenReturn(Optional.of("drv-3"));
        fx.as(driver("drv-3"));
        CrossTenant.assertDenied(() -> returns.pickedUp(rmaOfB.id(), "000000"));
    }
}
