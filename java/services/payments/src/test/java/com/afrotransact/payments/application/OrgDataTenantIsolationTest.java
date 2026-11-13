package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.payments.domain.FeeKind;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.NegotiatedRate;
import com.afrotransact.payments.domain.NegotiatedRateType;
import com.afrotransact.payments.domain.PayoutBalance;
import com.afrotransact.payments.domain.PayoutPolicy;
import com.afrotransact.payments.domain.PayoutRequest;
import com.afrotransact.payments.port.ConnectedAccountRepository;
import com.afrotransact.payments.port.DisputeRepository;
import com.afrotransact.payments.port.FeeScheduleRepository;
import com.afrotransact.payments.port.IdempotencyStore;
import com.afrotransact.payments.port.LabelChargeRepository;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.NegotiatedRateRepository;
import com.afrotransact.payments.port.PayoutHoldRepository;
import com.afrotransact.payments.port.PayoutRepository;
import com.afrotransact.payments.port.PlanFeeWithholdingRepository;
import com.afrotransact.payments.port.ProviderFeeRepository;
import com.afrotransact.payments.port.SettlementRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.TransferRecoveryRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tenant isolation of the organisation-owned money routes (payouts, balances, statements, label charges, provider
 * fees, plan fees, negotiated rates, rate card, ledger of an organisation, connected accounts). OpenFGA is off: the
 * real RBAC checkers, the JWT org claim and the owner checks must keep seller A away from seller B.
 */
class OrgDataTenantIsolationTest {

    private static final String ORG_A = TenantFixture.ORG_A;
    private static final String ORG_B = TenantFixture.ORG_B;

    private final TenantFixture tenants = new TenantFixture();
    private final PaymentsAccess access = new PaymentsAccess(tenants.permissions(), tenants.access(),
            tenants.resolver());
    private final PaymentsSettings settings = new PaymentsSettings("org_retail", Duration.ofDays(7), ZoneId.of("UTC"),
            "USD", 5, Duration.ofHours(1));
    private final MoneyTransactions tx = mock(MoneyTransactions.class);
    private final Clock clock = Clock.systemUTC();

    private final PayoutRepository payouts = mock(PayoutRepository.class);
    private final SettlementRepository settlements = mock(SettlementRepository.class);
    private final LedgerRepository ledger = mock(LedgerRepository.class);
    private final ConnectedAccountRepository accounts = mock(ConnectedAccountRepository.class);
    private final ProviderFeeRepository providerFees = mock(ProviderFeeRepository.class);
    private final PlanFeeWithholdingRepository planFees = mock(PlanFeeWithholdingRepository.class);
    private final PayoutPolicy policy = mock(PayoutPolicy.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final LabelChargeRepository labelCharges = mock(LabelChargeRepository.class);
    private final NegotiatedRateRepository rates = mock(NegotiatedRateRepository.class);

    private PayoutService payoutService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void wire() {
        when(tx.serializable(any())).thenAnswer(i -> ((Supplier<Object>) i.getArgument(0)).get());
        payoutService = new PayoutServiceImpl(payouts, settlements, ledger, accounts, mock(PayoutHoldRepository.class),
                providerFees, mock(StripeGateway.class), policy, idempotency, tx, access,
                mock(DomainEventPublisher.class), settings, planFees, mock(TransferRecoveryRepository.class), clock);
        when(settlements.transferredTo(anyString(), anyString())).thenReturn(Money.zero("USD"));
        when(payouts.totals(anyString(), anyString())).thenReturn(Map.of());
        when(providerFees.totalOf(anyString(), anyString())).thenReturn(Money.zero("USD"));
        when(planFees.outstanding(anyString(), anyString())).thenReturn(0L);
        when(ledger.payableByOrder(anyString(), anyString())).thenReturn(Map.of());
        when(policy.minimum(anyString())).thenReturn(Money.zero("USD"));
        when(accounts.find(anyString())).thenReturn(Optional.empty());
    }

    private static PayoutRequest payoutOf(String org) {
        return PayoutRequest.request(UUID.randomUUID(), org, Money.of(1000, "USD"), "user-" + org, Instant.now(),
                LocalDate.now());
    }

    // ------------------------------------------------------------------ payouts

    @Test
    void sellerAReadsNeitherTheStatusNorTheListNorDecidesOnSellerBsPayout() {
        PayoutRequest b = payoutOf(ORG_B);
        when(payouts.find(b.id())).thenReturn(Optional.of(b));
        when(payouts.lock(b.id())).thenReturn(Optional.of(b));
        tenants.as(TenantFixture.sellerOf(ORG_A, "payouts:read", "payouts:request", "payouts:approve",
                "payments:read"));

        CrossTenant.assertDenied(() -> payoutService.get(b.id()));
        CrossTenant.assertDenied(() -> payoutService.approve(b.id()));
        CrossTenant.assertDenied(() -> payoutService.hold(b.id(), "x"));
        CrossTenant.assertDenied(() -> payoutService.release(b.id()));
        CrossTenant.assertDenied(() -> payoutService.syncWithStripe(b.id()));
        CrossTenant.assertDenied(() -> payoutService.queue(null, ORG_B, 50));
        CrossTenant.assertDenied(() -> payoutService.queue(null, null, 50));
        CrossTenant.assertDenied(() -> payoutService.queue(null, "  ", 50));
        verify(payouts, never()).list(any(), any(), anyInt());
    }

    @Test
    void sellerAsPayoutQueueIsFilteredByTheirOwnOrganisation() {
        PayoutRequest a = payoutOf(ORG_A);
        when(payouts.list(any(), eq(Optional.of(ORG_A)), anyInt())).thenReturn(List.of(a));
        tenants.as(TenantFixture.sellerOf(ORG_A, "payouts:read"));

        List<PayoutRequest> rows = payoutService.queue(null, ORG_A, 50);

        CrossTenant.assertOnlyOwnedBy(rows, PayoutRequest::orgId, ORG_A);
        verify(payouts, never()).list(any(), eq(Optional.of(ORG_B)), anyInt());
    }

    @Test
    void sellerAAskingForSellerBsBalanceOnlyEverGetsTheirOwn() {
        tenants.as(TenantFixture.sellerOf(ORG_A, "payouts:read"));

        PayoutBalance balance = payoutService.balance(ORG_B, null);

        assertThat(balance.orgId()).isEqualTo(ORG_A);
        verify(settlements, never()).transferredTo(eq(ORG_B), anyString());
        verify(payouts, never()).totals(eq(ORG_B), anyString());
    }

    @Test
    void aPayoutRequestNamingSellerBIsStoredForSellerAOnly() {
        tenants.as(TenantFixture.sellerOf(ORG_A, "payouts:read", "payouts:request"));
        when(idempotency.claim(anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(accounts.find(ORG_A)).thenReturn(Optional.of("acct_a"));
        when(policy.validate(any(), any(), any(), any())).thenReturn(Money.of(500, "USD"));

        PayoutRequest made = payoutService.request(new PayoutCommand(ORG_B, 500L, false, "USD"), "key-1");

        assertThat(made.orgId()).isEqualTo(ORG_A);
        verify(payouts, never()).requestsOn(eq(ORG_B), any());
    }

    @Test
    void sellerAWithoutPayoutsReadSeesNeitherTheirOwnNorSellerBsBalance() {
        tenants.as(TenantFixture.sellerOf(ORG_A, "orders:read"));

        CrossTenant.assertDenied(() -> payoutService.balance(null, null));
        CrossTenant.assertDenied(() -> payoutService.balance(ORG_B, null));
        verify(settlements, never()).transferredTo(anyString(), anyString());
    }

    @Test
    void customersAndDriversHaveNoPayoutViews() {
        tenants.as(TenantFixture.customer("cust-1", "payouts:read"));
        CrossTenant.assertDenied(() -> payoutService.balance(ORG_B, null));
        CrossTenant.assertDenied(() -> payoutService.queue(null, ORG_B, 10));
        tenants.as(TenantFixture.driver("drv-1", "payouts:read"));
        CrossTenant.assertDenied(() -> payoutService.balance(ORG_B, null));
    }

    // ------------------------------------------------------------------ statement, labels, provider fees

    @Test
    void statementLabelChargesAndProviderFeesIgnoreAForeignOrgParameter() {
        tenants.as(TenantFixture.sellerOf(ORG_A, "payouts:read"));
        SellerStatementService statements = new SellerStatementServiceImpl(ledger, tx, access, settings, clock);
        LabelChargeService labels = new LabelChargeServiceImpl(labelCharges, mock(LedgerPoster.class),
                mock(InboxDeduplicator.class), tx, access, settings, clock);
        ProviderFeeService fees = new ProviderFeeServiceImpl(providerFees, accounts, mock(StripeGateway.class),
                mock(LedgerPoster.class), mock(InboxDeduplicator.class), tx, access,
                mock(DomainEventPublisher.class), settings, clock);

        statements.statement(ORG_B, "USD", null, null, 50);
        labels.ofOrg(ORG_B, null, null, null, 50);
        fees.ofOrg(ORG_B);

        verify(ledger).statement(eq(ORG_A), anyString(), any(), any(), anyInt(), any());
        verify(ledger, never()).statement(eq(ORG_B), anyString(), any(), any(), anyInt(), any());
        verify(labelCharges).ofOrg(eq(ORG_A), any(), any(), any(), anyInt());
        verify(labelCharges, never()).ofOrg(eq(ORG_B), any(), any(), any(), anyInt());
        verify(providerFees).ofOrg(eq(ORG_A), anyInt());
        verify(providerFees, never()).ofOrg(eq(ORG_B), anyInt());
    }

    // ------------------------------------------------------------------ plan fees

    @Test
    void sellerACannotReadWithholdOrReleaseSellerBsPlanFees() {
        PlanFeeService service = new PlanFeeServiceImpl(planFees, ledger, settlements, mock(DisputeRepository.class),
                mock(LedgerPoster.class), tx, access, settings, clock);
        tenants.as(TenantFixture.sellerOf(ORG_A, "payouts:read", "payments:plan-fees"));

        CrossTenant.assertDenied(() -> service.ofOrg(ORG_B, 50));
        CrossTenant.assertDenied(() -> service.withhold(ORG_B,
                new PlanFeeService.PlanFeeCommand("inv-1", "N-1", 100, "USD"), "plan-fee-inv-1"));
        CrossTenant.assertDenied(() -> service.release(ORG_B, "inv-1", null, null, "plan-fee-release-1"));
        verify(planFees, never()).ofOrg(anyString(), anyInt());
    }

    // ------------------------------------------------------------------ negotiated rates and rate card

    private static NegotiatedRate rateOf(String org) {
        return new NegotiatedRate(UUID.randomUUID(), org, NegotiatedRateType.COMMISSION, "*", 500, Instant.now(),
                null, "deal", "ops", null, "ops", Instant.now(), null, null, null, null, 0);
    }

    @Test
    void sellerACannotReadOrChangeSellerBsNegotiatedRates() {
        NegotiatedRate b = rateOf(ORG_B);
        when(rates.findById(b.id())).thenReturn(Optional.of(b));
        when(rates.search(eq(ORG_A), any(), anyInt())).thenReturn(List.of(rateOf(ORG_A)));
        NegotiatedRateService service = new NegotiatedRateServiceImpl(rates, tx, access, settings, clock);
        tenants.as(TenantFixture.sellerOf(ORG_A, "payouts:read", "payments:configure"));

        CrossTenant.assertDenied(() -> service.get(b.id()));
        CrossTenant.assertDenied(() -> service.end(b.id(), "x"));
        List<NegotiatedRateView> mine = service.list(ORG_B, null, null, 50);
        CrossTenant.assertOnlyOwnedBy(mine, v -> v.rate().orgId(), ORG_A);
        verify(rates, never()).search(eq(ORG_B), any(), anyInt());
    }

    @Test
    void sellerACannotReadSellerBsRateCardOrLedger() {
        FeeScheduleService fees = new FeeScheduleServiceImpl(mock(FeeScheduleRepository.class), rates,
                mock(OrgPlanService.class), tx, access, settings, clock);
        LedgerService ledgerService = new LedgerServiceImpl(ledger, access);
        tenants.as(TenantFixture.sellerOf(ORG_A, "payouts:read", "payments:read", "payments:fees.read"));

        CrossTenant.assertDenied(() -> fees.rateCard(ORG_B, null, null));
        CrossTenant.assertDenied(() -> fees.effective(FeeKind.PRODUCT, "x", ORG_B, null));
        CrossTenant.assertDenied(() -> ledgerService.balancesOfOrg(ORG_B));
        CrossTenant.assertDenied(ledgerService::balances);
        CrossTenant.assertDenied(() -> ledgerService.journalsOfOrder("ord-1"));
        verify(ledger, never()).balances(any());
    }

    @Test
    void sellerACannotReadOrRegisterSellerBsConnectedAccount() {
        ConnectedAccountService service = new ConnectedAccountServiceImpl(accounts, access, settings, clock);
        tenants.as(TenantFixture.sellerOf(ORG_A, "payouts:read", "payments:configure",
                "payments:connected-accounts"));

        CrossTenant.assertDenied(() -> service.accountOf(ORG_B));
        CrossTenant.assertDenied(() -> service.register(ORG_B, "acct_evil123"));
        verify(accounts, never()).find(ORG_B);
    }
}
