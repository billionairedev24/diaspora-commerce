package com.afrotransact.offers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.offers.application.OfferBatchService.PriceStockBatch;
import com.afrotransact.offers.application.OfferBatchService.PriceStockRow;
import com.afrotransact.offers.application.OfferBatchService.UpsertBatch;
import com.afrotransact.offers.domain.Coupon;
import com.afrotransact.offers.domain.CouponEvaluation;
import com.afrotransact.offers.domain.CouponFunding;
import com.afrotransact.offers.domain.CouponPolicy;
import com.afrotransact.offers.domain.CouponRejection;
import com.afrotransact.offers.domain.CouponScope;
import com.afrotransact.offers.domain.CouponStatus;
import com.afrotransact.offers.domain.CouponType;
import com.afrotransact.offers.domain.CouponUsage;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferPolicy;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.port.BuyBoxRepository;
import com.afrotransact.offers.port.CatalogClient;
import com.afrotransact.offers.port.CouponRepository;
import com.afrotransact.offers.port.ListingEntitlementsClient;
import com.afrotransact.offers.port.OfferAuditRepository;
import com.afrotransact.offers.port.OfferBatchMetrics;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.OfferRepository.OfferPage;
import com.afrotransact.offers.port.OfferRepository.OfferQuery;
import com.afrotransact.offers.port.OfferSettingsRepository;
import com.afrotransact.offers.port.OfferTaxMetrics;
import com.afrotransact.offers.port.PickupLocationClient;
import com.afrotransact.offers.port.SellerStandingRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Cross-tenant tests for the offers service (prod runs without OpenFGA: RBAC permission plus the signed org claim).
 * Tenant A's actor calls the real application services with tenant B's ids and org names. Named in
 * tenant-isolation-matrix.txt.
 */
class OffersTenantIsolationTest {

    private static final String ORG_A = TenantFixture.ORG_A;
    private static final String ORG_B = TenantFixture.ORG_B;
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final String ATIN = "AT00000002";
    private static final String RW = OffersPermissions.WRITE;
    private static final String RR = OffersPermissions.READ;

    private final TenantFixture tenants = new TenantFixture();
    private final OfferRepository offers = mock(OfferRepository.class);
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final CouponPolicy couponPolicy = mock(CouponPolicy.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final BuyBoxService buyBox = mock(BuyBoxService.class);
    private final BuyBoxEvaluator evaluator = mock(BuyBoxEvaluator.class);
    private final SellerStandingRepository standings = mock(SellerStandingRepository.class);
    private final OfferSettingsRepository settingsRepo = mock(OfferSettingsRepository.class);
    private final List<String> entitlementOrgs = new ArrayList<>();
    private OfferServiceImpl service;
    private OfferBatchServiceImpl batch;
    private CouponServiceImpl couponService;
    private SellerStandingServiceImpl standing;
    private BuyBoxServiceImpl buyBoxService;
    private OfferSettingsServiceImpl settings;
    private Offer offerOfB;
    private Coupon couponOfB;

    @BeforeEach
    void setUp() {
        OfferPolicy policy = new OfferPolicy(200, 108, 150, 200, null, null, 0);
        OfferSettingsService policies = mock(OfferSettingsService.class);
        when(policies.current()).thenReturn(policy);
        ListingEntitlementsClient entitlements = org -> {
            entitlementOrgs.add(org);
            return ListingEntitlementsClient.ListingAllowance.UNLIMITED;
        };
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new OfferServiceImpl(offers, mock(CatalogClient.class), mock(PickupLocationClient.class),
                entitlements, buyBox, evaluator, tenants.permissions(), tenants.access(), events,
                new OffersSettings("org_retail", 2, 2, 4, 5), mock(OfferTaxGate.class), mock(TaxHoldService.class),
                policies, mock(OfferTaxMetrics.class), clock);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        batch = new OfferBatchServiceImpl(service, offers, mock(OfferAuditRepository.class), buyBox,
                tenants.permissions(), tenants.access(), events, mock(OfferBatchMetrics.class), clock, tm);
        couponService = new CouponServiceImpl(coupons, offers, couponPolicy, tenants.permissions(),
                tenants.resolver(), events, clock);
        standing = new SellerStandingServiceImpl(standings, offers, buyBox, mock(InboxDeduplicator.class),
                tenants.permissions(), clock);
        buyBoxService = new BuyBoxServiceImpl(offers, mock(BuyBoxRepository.class), evaluator,
                tenants.permissions(), events, clock);
        settings = new OfferSettingsServiceImpl(settingsRepo, policy, tenants.permissions(), clock);

        offerOfB = offer(ORG_B, "SKU-B");
        when(offers.findById(offerOfB.id())).thenReturn(Optional.of(offerOfB));
        when(offers.update(any())).thenAnswer(i -> i.getArgument(0));
        when(offers.search(any())).thenReturn(new OfferPage(List.of(), 0, 0, 25));
        couponOfB = coupon(ORG_B);
        when(coupons.findById(couponOfB.id())).thenReturn(Optional.of(couponOfB));
        when(coupons.lockById(couponOfB.id())).thenReturn(Optional.of(couponOfB));
        when(coupons.findByCode("BCODE")).thenReturn(Optional.of(couponOfB));
    }

    private static Offer offer(String org, String sku) {
        return new Offer(UUID.randomUUID(), ATIN, "AT00000001", UUID.randomUUID(), null, UUID.randomUUID(), org,
                SellerKind.SELLER, sku, sku, OfferCondition.NEW, 1000, null, "USD", 0, Fulfilment.SELLER, 5, 2,
                new ShipFrom("Houston", "TX", "US", "77002"), OfferStatus.ACTIVE, true, NOW, NOW, 3, null, null,
                Map.of(), null, Offer.TAX_BASIS_SELLER_DECISION);
    }

    private static Coupon coupon(String org) {
        return new Coupon(UUID.randomUUID(), "BCODE", "d", CouponType.PERCENT, 10, "USD", null, CouponScope.SITEWIDE,
                null, 0, 100, 1, false, CouponFunding.SELLER, org, NOW, null, CouponStatus.ACTIVE, 0, "x", NOW, NOW, 0);
    }

    private void asSellerA() {
        tenants.as(TenantFixture.sellerOf(ORG_A, RR, RW, OffersPermissions.COUPONS_WRITE));
    }

    // ------------------------------------------------------------------ offers: ORG_OWNED

    @Test
    void aSellerCannotReadAnotherSellersOffer() {
        asSellerA();
        CrossTenant.assertDenied(() -> service.get(offerOfB.id()));
    }

    @Test
    void aSellerCannotChangePauseResumeOrWithdrawAnotherSellersOffer() {
        asSellerA();
        UpdateOfferCommand cmd = new UpdateOfferCommand(null, 1L, null, null, null, null, null, null, null);
        CrossTenant.assertDenied(() -> service.update(offerOfB.id(), cmd, null));
        CrossTenant.assertDenied(() -> service.update(offerOfB.id(), cmd));
        CrossTenant.assertDenied(() -> service.pause(offerOfB.id(), null));
        CrossTenant.assertDenied(() -> service.pause(offerOfB.id()));
        CrossTenant.assertDenied(() -> service.resume(offerOfB.id(), null));
        CrossTenant.assertDenied(() -> service.resume(offerOfB.id()));
        CrossTenant.assertDenied(() -> service.withdraw(offerOfB.id(), null));
        CrossTenant.assertDenied(() -> service.withdraw(offerOfB.id()));
        verify(offers, never()).update(any());
    }

    @Test
    void presenceOfAnotherOrganisationIsRefused() {
        asSellerA();
        CrossTenant.assertDenied(() -> service.hasOpenOffer(ORG_B, ATIN));
    }

    @Test
    void settingStockNamesOnlyTheCallersOwnOrganisationWhateverTheRequestSays() {
        asSellerA();
        when(offers.findOpenBySellerSku(ORG_A, "SKU-B")).thenReturn(List.of());
        CrossTenant.assertDenied(() -> service.setSellerStock("SKU-B", 9, "x", ORG_B));
        verify(offers).findOpenBySellerSku(ORG_A, "SKU-B");
        verify(offers, never()).findOpenBySellerSku(eq(ORG_B), anyString());
        verify(offers, never()).update(any());
    }

    @Test
    void createIgnoresASellerOrgNamedInTheBody() {
        asSellerA();
        CreateOfferCommand cmd = new CreateOfferCommand(ATIN, null, ORG_B, "ANK-1", null, OfferCondition.NEW, 1299,
                null, "USD", 0, Fulfilment.SELLER, 5, 2, new ShipFrom("Houston", "TX", "US", "77002"), null, null,
                Map.of(), Set.of());
        // the catalog mock cannot resolve the variant, so the call ends in 404 after the org was fixed
        assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
        assertThat(entitlementOrgs).containsExactly(ORG_A);
        verify(offers, never()).insert(any());
    }

    // ------------------------------------------------------------------ offers: ORG_SCOPED_LIST

    @Test
    void listFiltersByTheCallersOrgAndIgnoresASuppliedOne() {
        asSellerA();
        service.list(ORG_B, null, null, 0, 25);
        ArgumentCaptor<OfferQuery> query = ArgumentCaptor.forClass(OfferQuery.class);
        verify(offers).search(query.capture());
        assertThat(query.getValue().sellerOrgId()).isEqualTo(ORG_A);
    }

    @Test
    void aSellerWithoutAnOrganisationGetsNoListInsteadOfEveryone() {
        tenants.as(new Actor("u", PrincipalType.SELLER_MEMBER, Optional.empty(), Set.of(), Set.of(RR)));
        assertThatThrownBy(() -> service.list(null, null, null, 0, 25)).isInstanceOf(OfferRuleViolation.class);
        assertThatThrownBy(() -> service.list("", null, null, 0, 25)).isInstanceOf(OfferRuleViolation.class);
        verify(offers, never()).search(any());
    }

    @Test
    void staffMayFilterByAnyOrganisation() {
        tenants.as(TenantFixture.staff(RR));
        service.list(ORG_B, null, null, 0, 25);
        ArgumentCaptor<OfferQuery> query = ArgumentCaptor.forClass(OfferQuery.class);
        verify(offers).search(query.capture());
        assertThat(query.getValue().sellerOrgId()).isEqualTo(ORG_B);
    }

    // ------------------------------------------------------------------ batch: every row is scoped to the org

    @Test
    void batchUpsertForAnotherOrganisationIsRefusedBeforeAnyRow() {
        asSellerA();
        UpsertBatch body = new UpsertBatch(ORG_B, null, "x", List.of());
        CrossTenant.assertDenied(() -> batch.upsert(body));
        verify(offers, never()).findOpen(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void batchPricesAndStockForAnotherOrganisationIsRefused() {
        asSellerA();
        PriceStockBatch body = new PriceStockBatch(ORG_B, null, "x",
                List.of(new PriceStockRow("1", "SKU-B", null, 1L, null, null)));
        CrossTenant.assertDenied(() -> batch.setPricesAndStock(body));
        verify(offers, never()).findOpenBySellerSku(anyString(), anyString());
    }

    @Test
    void batchPricesAndStockOnlyLooksAtTheCallersOwnOffers() {
        asSellerA();
        PriceStockBatch body = new PriceStockBatch(null, null, "x",
                List.of(new PriceStockRow("1", "SKU-B", null, 1L, null, null)));
        batch.setPricesAndStock(body);
        verify(offers).findOpenBySellerSku(ORG_A, "SKU-B");
        verify(offers, never()).update(any());
    }

    // ------------------------------------------------------------------ coupons

    @Test
    void aSellerCannotReadOrChangeAnotherSellersCoupon() {
        asSellerA();
        CrossTenant.assertDenied(() -> couponService.get(couponOfB.id()));
        CrossTenant.assertDenied(() -> couponService.changeStatus(couponOfB.id(), CouponStatus.PAUSED));
        verify(coupons, never()).update(any());
    }

    @Test
    void couponListIsPinnedToTheCallersOrgForSellers() {
        asSellerA();
        couponService.list(CouponFunding.PLATFORM, ORG_B, null);
        verify(coupons).list(CouponFunding.SELLER, ORG_A, null);
        verify(coupons, never()).list(any(), eq(ORG_B), any());
    }

    @Test
    void sellerCreatedCouponIsAlwaysFundedByTheCallersOrg() {
        asSellerA();
        when(coupons.insert(any())).thenAnswer(i -> i.getArgument(0));
        CreateCouponCommand cmd = new CreateCouponCommand("NEWCODE", "d", CouponType.PERCENT, 10, "USD", null,
                CouponScope.SITEWIDE, null, 0, 10, 1, false, CouponFunding.SELLER, ORG_B, null, null);
        Coupon saved = couponService.create(cmd);
        assertThat(saved.fundedByOrg()).isEqualTo(ORG_A);
    }

    @Test
    void aSellerCannotCreatePlatformFundedCoupons() {
        asSellerA();
        CreateCouponCommand cmd = new CreateCouponCommand("NEWCODE", "d", CouponType.PERCENT, 10, "USD", null,
                CouponScope.SITEWIDE, null, 0, 10, 1, false, CouponFunding.PLATFORM, null, null, null);
        CrossTenant.assertDenied(() -> couponService.create(cmd));
    }

    @Test
    void redeemAndReleaseAreNotForOrgMembersOrCustomers() {
        asSellerA();
        CouponCartCommand cart = new CouponCartCommand("BCODE", "cust-1", "o-1", "USD",
                List.of(new CouponCartCommand.Line(offerOfB.id(), 1)), 0, false);
        CrossTenant.assertDenied(() -> couponService.redeem("key", cart));
        CrossTenant.assertDenied(() -> couponService.release(UUID.randomUUID()));
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        CrossTenant.assertDenied(() -> couponService.redeem("key", cart));
        CrossTenant.assertDenied(() -> couponService.release(UUID.randomUUID()));
    }

    @Test
    void clipRecordsTheTokenSubjectAndOrgMembersCannotClip() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        Coupon clippable = new Coupon(UUID.randomUUID(), "CLIP", "d", CouponType.PERCENT, 10, "USD", null,
                CouponScope.SITEWIDE, null, 0, 100, 1, true, CouponFunding.PLATFORM, null, NOW, null,
                CouponStatus.ACTIVE, 0, "x", NOW, NOW, 0);
        when(coupons.findByCode("CLIP")).thenReturn(Optional.of(clippable));
        couponService.clip("clip");
        verify(coupons).clip(eq(clippable.id()), eq(TenantFixture.CUSTOMER_1), any());
        tenants.as(TenantFixture.sellerOf(ORG_A, RR, RW));
        CrossTenant.assertDenied(() -> couponService.clip("clip"));
    }

    @Test
    void validateByAnOrgMemberNeverReadsAnotherCustomersUsage() {
        asSellerA();
        Coupon limited = coupon(ORG_A);
        when(coupons.findByCode("BCODE")).thenReturn(Optional.of(limited));
        when(coupons.redeemedCount(limited.id(), TenantFixture.CUSTOMER_1)).thenReturn(7L);
        when(coupons.clipped(limited.id(), TenantFixture.CUSTOMER_1)).thenReturn(true);
        when(couponPolicy.evaluate(any(), any(), any(), any()))
                .thenReturn(CouponEvaluation.rejected(CouponRejection.NO_ELIGIBLE_ITEMS, "n"));
        CouponCartCommand cart = new CouponCartCommand("BCODE", TenantFixture.CUSTOMER_1, null, "USD",
                List.of(new CouponCartCommand.Line(offerOfB.id(), 1)), 0, false);
        couponService.validate(cart);
        ArgumentCaptor<CouponUsage> usage = ArgumentCaptor.forClass(CouponUsage.class);
        verify(couponPolicy).evaluate(any(), any(), usage.capture(), any());
        assertThat(usage.getValue().customerRedeemed()).isZero();
        assertThat(usage.getValue().clipped()).isFalse();
        verify(coupons, never()).redeemedCount(any(), eq(TenantFixture.CUSTOMER_1));
    }

    @Test
    void validateByACustomerUsesTheTokenSubjectNotTheBodyCustomer() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        Coupon c = coupon(ORG_A);
        when(coupons.findByCode("BCODE")).thenReturn(Optional.of(c));
        when(couponPolicy.evaluate(any(), any(), any(), any()))
                .thenReturn(CouponEvaluation.rejected(CouponRejection.NO_ELIGIBLE_ITEMS, "n"));
        CouponCartCommand cart = new CouponCartCommand("BCODE", TenantFixture.CUSTOMER_2, null, "USD",
                List.of(new CouponCartCommand.Line(offerOfB.id(), 1)), 0, false);
        couponService.validate(cart);
        verify(coupons).redeemedCount(c.id(), TenantFixture.CUSTOMER_1);
        verify(coupons, never()).redeemedCount(c.id(), TenantFixture.CUSTOMER_2);
    }

    // ------------------------------------------------------------------ standing, buy box, settings

    @Test
    void sellerStandingOfAnotherOrganisationIsRefused() {
        asSellerA();
        CrossTenant.assertDenied(() -> standing.get(ORG_B));
        verify(standings, never()).find(ORG_B);
    }

    @Test
    void sellerPerformanceIsStaffOnly() {
        asSellerA();
        SellerPerformanceCommand cmd = new SellerPerformanceCommand(4.0, 0.9, 0.1, false);
        CrossTenant.assertDenied(() -> standing.updatePerformance(ORG_A, cmd));
        CrossTenant.assertDenied(() -> standing.updatePerformance(ORG_B, cmd));
        verify(standings, never()).save(any());
    }

    @Test
    void buyBoxHistoryIsStaffOnly() {
        asSellerA();
        CrossTenant.assertDenied(() -> buyBoxService.history(ATIN, 10));
    }

    @Test
    void offerSettingsAreStaffOnly() {
        asSellerA();
        CrossTenant.assertDenied(() -> settings.update(new OfferPolicy(200, 108, 150, 200, null, null, 0)));
        verify(settingsRepo, never()).save(any());
    }
}
