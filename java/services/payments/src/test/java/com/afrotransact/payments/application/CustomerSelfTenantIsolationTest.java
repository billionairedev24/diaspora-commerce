package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.payments.domain.GiftCard;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentSplitter;
import com.afrotransact.payments.domain.SavedPaymentMethod;
import com.afrotransact.payments.port.CreditAdjustmentRepository;
import com.afrotransact.payments.port.CreditLedgerRepository;
import com.afrotransact.payments.port.FeeScheduleRepository;
import com.afrotransact.payments.port.GiftCardAttemptRepository;
import com.afrotransact.payments.port.GiftCardRepository;
import com.afrotransact.payments.port.GiftCodeHasher;
import com.afrotransact.payments.port.IdempotencyStore;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.MembershipActivityRepository;
import com.afrotransact.payments.port.MembershipChargeRepository;
import com.afrotransact.payments.port.MembershipRepository;
import com.afrotransact.payments.port.MembershipSettingsRepository;
import com.afrotransact.payments.port.MembershipTierRepository;
import com.afrotransact.payments.port.MoneySettingsRepository;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.payments.port.ReferralRepository;
import com.afrotransact.payments.port.ReferralSettingsRepository;
import com.afrotransact.payments.port.SettlementRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.WalletRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Shopper-owned payments routes (saved payment methods, gift cards bought, credit, referrals, the payment of an
 * order) compare the row's customer with the token subject; staff, service and org-member routes refuse shoppers
 * and sellers.
 */
class CustomerSelfTenantIsolationTest {

    private static final String C1 = TenantFixture.CUSTOMER_1;
    private static final String C2 = TenantFixture.CUSTOMER_2;

    private final TenantFixture tenants = new TenantFixture();
    private final PaymentsAccess access = new PaymentsAccess(tenants.permissions(), tenants.access(),
            tenants.resolver());
    private final PaymentsSettings settings = new PaymentsSettings("org_retail", Duration.ofDays(7), ZoneId.of("UTC"),
            "USD", 5, Duration.ofHours(1));
    private final MoneyTransactions tx = mock(MoneyTransactions.class);
    private final Clock clock = Clock.systemUTC();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void wire() {
        when(tx.serializable(any())).thenAnswer(i -> ((Supplier<Object>) i.getArgument(0)).get());
    }

    @Test
    void customer1CannotChangeOrRemoveCustomer2sSavedPaymentMethod() {
        WalletRepository wallet = mock(WalletRepository.class);
        SavedPaymentMethod theirs = mock(SavedPaymentMethod.class);
        when(theirs.customerId()).thenReturn(C2);
        when(wallet.findMethod("pm-2")).thenReturn(Optional.of(theirs));
        when(wallet.lockCustomer(C1)).thenReturn(Optional.of(new WalletRepository.StripeCustomer(C1, "cus_1", null)));
        PaymentMethodService service = new PaymentMethodServiceImpl(wallet, mock(WalletSync.class),
                mock(StripeGateway.class), tx, access, clock);
        tenants.as(TenantFixture.customer(C1));

        CrossTenant.assertDenied(() -> service.setDefault("pm-2"));
        CrossTenant.assertDenied(() -> service.remove("pm-2"));
        verify(wallet, never()).markDetached(eq("pm-2"), any());
    }

    @Test
    void sellersAndDriversHaveNoWallet() {
        PaymentMethodService service = new PaymentMethodServiceImpl(mock(WalletRepository.class),
                mock(WalletSync.class), mock(StripeGateway.class), tx, access, clock);
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "payments:read"));
        CrossTenant.assertDenied(service::list);
        tenants.as(TenantFixture.driver("drv-1"));
        CrossTenant.assertDenied(service::list);
    }

    private GiftCardService giftCards(GiftCardRepository cards) {
        return new GiftCardServiceImpl(cards, mock(GiftCardAttemptRepository.class), mock(GiftCodeHasher.class),
                mock(CreditLedger.class), mock(LedgerPoster.class), tx, mock(StripeGateway.class),
                mock(IdempotencyStore.class), mock(MoneySettingsRepository.class), mock(FeeScheduleRepository.class),
                mock(DomainEventPublisher.class), access, mock(GiftCardSettings.class), settings, clock);
    }

    @Test
    void customer1CannotConfirmCustomer2sGiftCardAndSeesOnlyTheirOwn() {
        GiftCardRepository cards = mock(GiftCardRepository.class);
        UUID id = UUID.randomUUID();
        GiftCard theirs = mock(GiftCard.class);
        when(theirs.purchaserId()).thenReturn(C2);
        when(cards.lock(id)).thenReturn(Optional.of(theirs));
        when(cards.ofPurchaser(eq(C1), anyInt())).thenReturn(List.of());
        GiftCardService service = giftCards(cards);
        tenants.as(TenantFixture.customer(C1));

        CrossTenant.assertDenied(() -> service.confirmPurchase(id));
        service.myPurchases();

        verify(cards).ofPurchaser(eq(C1), anyInt());
        verify(cards, never()).ofPurchaser(eq(C2), anyInt());
    }

    @Test
    void creditOfAnotherCustomerIsAStaffViewAndMyViewsUseTheTokenSubject() {
        CreditLedgerRepository entries = mock(CreditLedgerRepository.class);
        CreditService service = new CreditServiceImpl(mock(CreditLedger.class), entries,
                mock(CreditAdjustmentRepository.class), mock(MoneySettingsRepository.class),
                mock(GiftCardRepository.class), mock(LedgerRepository.class), tx, mock(DomainEventPublisher.class),
                access, settings, clock);
        tenants.as(TenantFixture.customer(C1, "payments:read", "payments:service"));

        service.myEntries(null, 10);
        service.myBalance();
        CrossTenant.assertDenied(() -> service.balanceOf(C2));
        CrossTenant.assertDenied(() -> service.customer(C2, 10));

        verify(entries).entries(eq(C1), any(), anyInt());
        verify(entries, never()).entries(eq(C2), any(), anyInt());
        verify(entries, never()).balance(C2);
    }

    @Test
    void referralsOfOthersAreStaffViewsAndMyReferralsUseTheTokenSubject() {
        ReferralRepository referrals = mock(ReferralRepository.class);
        when(referrals.ofReferrer(eq(C1), anyInt())).thenReturn(List.of());
        ReferralService service = new ReferralServiceImpl(referrals, mock(ReferralSettingsRepository.class),
                mock(ReferralRewarder.class), tx, access, settings, mock(DomainEventPublisher.class), clock);
        tenants.as(TenantFixture.customer(C1, "payments:read"));

        service.myReferrals(10);
        CrossTenant.assertDenied(() -> service.get(UUID.randomUUID()));
        CrossTenant.assertDenied(() -> service.list(null, C2, 10));
        CrossTenant.assertDenied(service::summary);

        verify(referrals).ofReferrer(eq(C1), anyInt());
        verify(referrals, never()).ofReferrer(eq(C2), anyInt());
        verify(referrals, never()).search(any(), any(), anyInt());
    }

    @Test
    void membershipOfAnotherCustomerIsAStaffView() {
        MembershipRepository memberships = mock(MembershipRepository.class);
        MembershipService service = new MembershipServiceImpl(memberships, mock(MembershipTierRepository.class),
                mock(MembershipChargeRepository.class), mock(MembershipActivityRepository.class),
                mock(MembershipSettingsRepository.class), mock(MembershipCharger.class),
                mock(MembershipCredits.class), mock(MembershipRecorder.class), mock(PaymentMethodService.class),
                mock(StripeGateway.class), mock(LedgerPoster.class), mock(IdempotencyStore.class), tx, access, clock);
        tenants.as(TenantFixture.customer(C1, "payments:read"));

        CrossTenant.assertDenied(() -> service.ofCustomer(C2));
        CrossTenant.assertDenied(() -> service.members(null, null, null, 10));
        CrossTenant.assertDenied(() -> service.timeline(UUID.randomUUID()));
    }

    @Test
    void customer1CannotReadCustomer2sPaymentByOrderAndSellersCannotReadAny() {
        PaymentRepository payments = mock(PaymentRepository.class);
        Payment theirs = mock(Payment.class);
        when(theirs.metadata()).thenReturn(Map.of("customer_id", C2));
        when(theirs.customerId()).thenReturn("cus_2");
        when(payments.findByOrderId("ord-2")).thenReturn(Optional.of(theirs));
        WalletSync wallet = mock(WalletSync.class);
        PaymentService service = new PaymentServiceImpl(payments, mock(FeeScheduleRepository.class),
                mock(SettlementRepository.class), mock(StripeGateway.class), mock(PaymentSplitter.class),
                mock(LedgerPoster.class), mock(IdempotencyStore.class), mock(PaymentStatusUpdater.class), wallet,
                mock(WalletRepository.class), tx, access, mock(DomainEventPublisher.class), settings,
                mock(OrgPlanService.class), mock(LineRates.class), mock(CreditLedger.class),
                mock(MembershipBenefitsService.class), mock(TransferRecoveryLedger.class), clock);

        tenants.as(TenantFixture.customer(C1, "payments:read"));
        CrossTenant.assertDenied(() -> service.getByOrder("ord-2"));
        CrossTenant.assertDenied(() -> service.get(UUID.randomUUID(), false));
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "payments:read", "payments:service"));
        CrossTenant.assertDenied(() -> service.getByOrder("ord-2"));
        CrossTenant.assertDenied(() -> service.get(UUID.randomUUID(), false));
        tenants.as(TenantFixture.customer(C2));
        assertThat(service.getByOrder("ord-2")).isSameAs(theirs);
    }

    @Test
    void staffServiceAndConfigurationGatesRefuseSellersAndShoppers() {
        for (var actor : List.of(
                TenantFixture.sellerOf(TenantFixture.ORG_A, "payments:read", "payments:configure", "payments:service",
                        "ledger:adjust", "ledger:approve", "payouts:approve", "payments:fees.read",
                        "payments:credit.grant", "payments:plan-fees", "payments:connected-accounts"),
                TenantFixture.customer(C1, "payments:read", "payments:configure", "payments:service", "ledger:adjust",
                        "ledger:approve", "payouts:approve", "payments:credit.grant"))) {
            tenants.as(actor);
            CrossTenant.assertDenied(access::reader);
            CrossTenant.assertDenied(access::configure);
            CrossTenant.assertDenied(access::adjuster);
            CrossTenant.assertDenied(access::adjustmentApprover);
            CrossTenant.assertDenied(access::approver);
            CrossTenant.assertDenied(access::supplierPaymentApprover);
            CrossTenant.assertDenied(access::service);
            CrossTenant.assertDenied(access::creditReader);
            CrossTenant.assertDenied(access::creditGranter);
            CrossTenant.assertDenied(access::feeReader);
            CrossTenant.assertDenied(access::deliveryQuoter);
            CrossTenant.assertDenied(access::planFeeCollector);
            CrossTenant.assertDenied(access::accountRegistrar);
        }
    }
}
