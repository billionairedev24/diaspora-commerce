package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.domain.barcode.LabelPolicy;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.ProductReview;
import com.afrotransact.catalog.domain.ReviewContentPolicy;
import com.afrotransact.catalog.domain.ReviewReasonCode;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.SavedDraft;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.domain.tax.TaxEvidence;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.ImageLibraryLookup;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.catalog.port.GtinExemptionRepository;
import com.afrotransact.catalog.port.TaxonomyAuditRepository;
import com.afrotransact.catalog.port.ImportMetrics;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.MatchMetrics;
import com.afrotransact.catalog.port.UnitLabelRepository;
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.catalog.port.ProductImportRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.PurchaseVerifier;
import com.afrotransact.catalog.port.ProductReviewRepository;
import com.afrotransact.catalog.port.SellerOffers;
import com.afrotransact.catalog.port.SizeChartRepository;
import com.afrotransact.catalog.port.SpreadsheetReader;
import com.afrotransact.catalog.port.SpreadsheetWriter;
import com.afrotransact.catalog.port.TaxEvidenceRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Cross-tenant tests for catalog (prod runs without OpenFGA: RBAC permission plus the signed org claim and per-resource
 * owner checks). Seller A holds catalog:write, as the seller roles do; every id below belongs to seller B.
 * Named in tenant-isolation-matrix.txt.
 */
class CatalogTenantIsolationTest {

    private static final String ORG_A = TenantFixture.ORG_A;
    private static final String ORG_B = TenantFixture.ORG_B;
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final TenantFixture tenants = new TenantFixture();
    private final ProductRepository products = mock(ProductRepository.class);
    private final ChangeRequestRepository requests = mock(ChangeRequestRepository.class);
    private final ProductDraftRepository drafts = mock(ProductDraftRepository.class);
    private final SizeChartRepository charts = mock(SizeChartRepository.class);
    private final ProductImportRepository imports = mock(ProductImportRepository.class);
    private final ProductReviewRepository reviews = mock(ProductReviewRepository.class);
    private final TaxEvidenceRepository evidence = mock(TaxEvidenceRepository.class);
    private final OwnerResolver owners = new RetailAwareOwnerResolver("org_retail");
    private ProductServiceImpl productService;
    private ProductTaxServiceImpl taxService;
    private ChangeRequestServiceImpl changeRequests;
    private ProductDraftServiceImpl draftService;
    private SizeChartServiceImpl sizeCharts;
    private SellerReviewServiceImpl sellerReviews;
    private ProductImportServiceImpl importService;
    private Product pageOfB;
    private ProductChangeRequest requestOfB;
    private SavedDraft draftOfB;
    private SizeChart chartOfB;
    private ProductImport importOfB;
    private ProductReview reviewOfB;
    private TaxEvidence evidenceOfB;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(products, mock(ProductWriter.class), tenants.permissions(),
                tenants.access(), tenants.resolver(), owners, mock(ProductEvents.class), mock(ProductLiveGate.class),
                CLOCK, mock(SellingPublishGate.class));
        taxService = new ProductTaxServiceImpl(products, mock(CategoryRepository.class), evidence,
                mock(TaxGateEvaluator.class), mock(ProductEvents.class), mock(ProductLiveGate.class),
                tenants.permissions(), tenants.access(), CLOCK);
        changeRequests = new ChangeRequestServiceImpl(requests, products, mock(SellerOffers.class),
                mock(CatalogSettingsService.class), mock(ChangeRequestApplier.class), mock(PageMerger.class),
                mock(PageVariantService.class), mock(ChangeRequestEvents.class), mock(ProductEvents.class),
                tenants.permissions(), CLOCK);
        draftService = new ProductDraftServiceImpl(drafts, products, tenants.permissions(), tenants.access(), owners,
                new UnrestrictedScopeGuard(), CLOCK);
        sizeCharts = new SizeChartServiceImpl(charts, products, mock(CategoryRepository.class),
                tenants.permissions(), tenants.access(), owners, mock(SpreadsheetReader.class),
                mock(SpreadsheetWriter.class), new UnrestrictedScopeGuard(), CLOCK);
        sellerReviews = new SellerReviewServiceImpl(reviews, tenants.permissions(), tenants.access(),
                mock(ReviewEvents.class), CLOCK);
        importService = new ProductImportServiceImpl(imports, products, charts, mock(CategoryRepository.class),
                mock(SpreadsheetReader.class), mock(SpreadsheetWriter.class), mock(ImportJob.class),
                tenants.permissions(), tenants.access(), owners, mock(ImportSettings.class),
                mock(ImageLibraryLookup.class), mock(Executor.class), new UnrestrictedScopeGuard(), CLOCK,
                mock(PlatformTransactionManager.class), mock(ListingRulesService.class), mock(ImportMatcher.class),
                mock(ImportMetrics.class), mock(DomainEventPublisher.class));

        Variant v = new Variant(UUID.randomUUID(), new Atin("AT00000002"), "S-1", "012345678905", false, Map.of(), 899,
                "USD", 5);
        pageOfB = new Product(UUID.randomUUID(), new Atin("AT00000001"),
                new ListingOwner(ListingOwnerKind.SELLER, ORG_B), null, null, "custom", "Ankara tote", Map.of(),
                List.of(), "", List.of(v), ProductStatus.IN_REVIEW, null, NOW, NOW, 0);
        when(products.findById(pageOfB.id())).thenReturn(Optional.of(pageOfB));
        when(products.findByAtin(pageOfB.atin())).thenReturn(Optional.of(pageOfB));
        when(products.findByVariantAtin(v.atin())).thenReturn(Optional.of(pageOfB));
        requestOfB = ProductChangeRequest.filed(pageOfB.id(), "AT00000001", "AT00000002", "00012345678905",
                ChangeRequestType.WRONG_MATCH, "", Map.of("gtin", "012345678905", "variantAtin", "AT00000002"), null,
                ORG_B, "user-b", NOW, NOW);
        when(requests.findById(requestOfB.id())).thenReturn(Optional.of(requestOfB));
        draftOfB = mock(SavedDraft.class);
        when(draftOfB.ownerOrg()).thenReturn(ORG_B);
        when(draftOfB.id()).thenReturn(UUID.randomUUID());
        when(drafts.findById(draftOfB.id())).thenReturn(Optional.of(draftOfB));
        chartOfB = mock(SizeChart.class);
        when(chartOfB.ownerOrg()).thenReturn(ORG_B);
        when(chartOfB.id()).thenReturn(UUID.randomUUID());
        when(charts.findById(chartOfB.id())).thenReturn(Optional.of(chartOfB));
        importOfB = mock(ProductImport.class);
        when(importOfB.owner()).thenReturn(new ListingOwner(ListingOwnerKind.SELLER, ORG_B));
        when(importOfB.id()).thenReturn(UUID.randomUUID());
        when(imports.findById(importOfB.id())).thenReturn(Optional.of(importOfB));
        reviewOfB = mock(ProductReview.class);
        when(reviewOfB.sellerOrgId()).thenReturn(ORG_B);
        when(reviewOfB.id()).thenReturn(UUID.randomUUID());
        when(reviews.findById(reviewOfB.id())).thenReturn(Optional.of(reviewOfB));
        evidenceOfB = mock(TaxEvidence.class);
        when(evidenceOfB.variantAtin()).thenReturn("AT00000002");
        when(evidenceOfB.id()).thenReturn(UUID.randomUUID());
        when(evidence.findById(evidenceOfB.id())).thenReturn(Optional.of(evidenceOfB));
        tenants.as(TenantFixture.sellerOf(ORG_A, "catalog:write"));
    }

    // ------------------------------------------------------------------ product pages

    @Test
    void aSellerCannotReadOrChangeAnotherSellersProduct() {
        UUID id = pageOfB.id();
        CrossTenant.assertDenied(() -> productService.get(id));
        CrossTenant.assertDenied(() -> productService.getByAtin("AT00000001"));
        CrossTenant.assertDenied(() -> productService.getByAtin("AT00000002"));
        CrossTenant.assertDenied(() -> productService.variant("AT00000002"));
        CrossTenant.assertDenied(() -> productService.update(id, null));
        CrossTenant.assertDenied(() -> productService.pause(id));
        CrossTenant.assertDenied(() -> productService.resume(id));
        CrossTenant.assertDenied(() -> productService.setPreorderFlags(id, true, false));
        verify(products, never()).update(any());
    }

    @Test
    void productListIgnoresAnOwnerOrgNamedByASeller() {
        when(products.search(any())).thenReturn(new ProductRepository.ProductPage(List.of(), 0, 0, 25));
        productService.list(ORG_B, null, false, null, 0, 25);
        ArgumentCaptor<ProductRepository.ProductQuery> q =
                ArgumentCaptor.forClass(ProductRepository.ProductQuery.class);
        verify(products).search(q.capture());
        assertThat(q.getValue().ownerOrgId()).isEqualTo(ORG_A);
    }

    // ------------------------------------------------------------------ tax readiness and evidence

    @Test
    void aSellerCannotReadOrUploadTaxEvidenceOfAnotherSellersVariant() {
        CrossTenant.assertDenied(() -> taxService.readiness(pageOfB.id()));
        CrossTenant.assertDenied(() -> taxService.evidence("AT00000002"));
        CrossTenant.assertDenied(() -> taxService.evidenceFile(evidenceOfB.id()));
        CrossTenant.assertDenied(() -> taxService.uploadEvidence("AT00000002", "fact", "f.pdf", "application/pdf",
                new byte[] {1}));
        verify(evidence, never()).store(any(), any());
    }

    // ------------------------------------------------------------------ change requests

    @Test
    void aSellerCannotReadOrWithdrawAnotherSellersChangeRequest() {
        CrossTenant.assertDenied(() -> changeRequests.get(requestOfB.id()));
        CrossTenant.assertDenied(() -> changeRequests.withdraw(requestOfB.id()));
        verify(requests, never()).update(any());
    }

    @Test
    void forPageShowsOnlyTheCallersOwnRequestsOnSomeoneElsesPage() {
        changeRequests.forPage("AT00000001");
        verify(requests).findByProduct(pageOfB.id(), ORG_A);
    }

    // ------------------------------------------------------------------ drafts, size charts, imports

    @Test
    void aSellerCannotReadSaveOrDeleteAnotherSellersDraft() {
        UUID id = draftOfB.id();
        CrossTenant.assertDenied(() -> draftService.get(id));
        CrossTenant.assertDenied(() -> draftService.save(id, 0, "t", "Fashion", "{}"));
        CrossTenant.assertDenied(() -> draftService.delete(id));
        verify(drafts, never()).delete(any());
        verify(drafts, never()).update(any());
    }

    @Test
    void aSellerCannotReadOrChangeAnotherSellersSizeChart() {
        UUID id = chartOfB.id();
        CrossTenant.assertDenied(() -> sizeCharts.get(id));
        CrossTenant.assertDenied(() -> sizeCharts.update(id, null, null));
        CrossTenant.assertDenied(() -> sizeCharts.retire(id));
        CrossTenant.assertDenied(() -> sizeCharts.duplicate(id, "copy"));
        verify(charts, never()).update(any());
    }

    @Test
    void sizeChartListIgnoresAnOwnerOrgNamedByASeller() {
        when(charts.list(any())).thenReturn(new SizeChartRepository.ChartPage(List.of(), 0, 0, 25));
        sizeCharts.list(ORG_B, null, null, false, null, 0, 25);
        ArgumentCaptor<SizeChartRepository.ChartQuery> q =
                ArgumentCaptor.forClass(SizeChartRepository.ChartQuery.class);
        verify(charts).list(q.capture());
        assertThat(q.getValue().ownerOrg()).isEqualTo(ORG_A);
    }

    @Test
    void aSellerCannotReadOrStartAnotherSellersImport() {
        UUID id = importOfB.id();
        CrossTenant.assertDenied(() -> importService.get(id));
        CrossTenant.assertDenied(() -> importService.rows(id, null, null, 0, 25));
        CrossTenant.assertDenied(() -> importService.report(id, SpreadsheetFormat.CSV));
        CrossTenant.assertDenied(() -> importService.start(id, false));
        verify(imports, never()).update(any());
    }

    // ------------------------------------------------------------------ the owner of every "mine" route

    @Test
    void theListingOwnerIsAlwaysTheTokenOrganisation() {
        assertThat(owners.ownerFor(TenantFixture.sellerOf(ORG_A, "catalog:write")).orgId()).isEqualTo(ORG_A);
        CrossTenant.assertDenied(() -> owners.ownerFor(TenantFixture.customer(TenantFixture.CUSTOMER_1,
                "catalog:write")));
        CrossTenant.assertDenied(() -> owners.ownerFor(TenantFixture.staff("catalog:write")));
    }

    // ------------------------------------------------------------------ own-organisation lists

    @Test
    void ownListsAreAlwaysBuiltFromTheCallersOrganisation() {
        when(drafts.findByOwnerOrg(any(), anyInt())).thenReturn(List.of());
        draftService.list();
        verify(drafts).findByOwnerOrg(eq(ORG_A), anyInt());

        when(requests.search(any())).thenReturn(new ChangeRequestRepository.Page(List.of(), 0, 0, 25));
        changeRequests.mine(null, 0, 25);
        ArgumentCaptor<ChangeRequestRepository.Query> q = ArgumentCaptor.forClass(ChangeRequestRepository.Query.class);
        verify(requests).search(q.capture());
        assertThat(q.getValue().requestedByOrg()).isEqualTo(ORG_A);

        GtinExemptionRepository exemptions = mock(GtinExemptionRepository.class);
        new GtinExemptionServiceImpl(products, exemptions, mock(CatalogSettingsService.class),
                mock(ChangeRequestEvents.class), mock(ProductEvents.class), tenants.permissions(), CLOCK).mine();
        verify(exemptions).findByOrg(ORG_A);

        CategoryRequestRepository categoryRequests = mock(CategoryRequestRepository.class);
        new CategoryServiceImpl(mock(CategoryRepository.class), categoryRequests, products,
                mock(IdentifierIssuer.class), tenants.permissions(), owners, mock(DomainEventPublisher.class),
                mock(ProductEvents.class), mock(TaxonomyAdminService.class), mock(TaxonomyAuditRepository.class),
                new UnrestrictedScopeGuard(), CLOCK).myRequests();
        verify(categoryRequests).findByOrg(ORG_A);

        when(products.findByOwnerOrg(any())).thenReturn(List.of());
        new ImageLibraryServiceImpl(products, mock(ProductWriter.class), mock(ImageLibraryLookup.class),
                tenants.permissions(), tenants.access(), owners, mock(PlatformTransactionManager.class)).skuList();
        verify(products).findByOwnerOrg(ORG_A);
    }

    // ------------------------------------------------------------------ matching, eligibility, labels

    @Test
    void matchingAsksTheEligibilityRuleForTheCallersOrgNotTheOneInTheBody() {
        CatalogSettingsService settings = mock(CatalogSettingsService.class);
        when(settings.current()).thenReturn(new CatalogPolicy(List.of("02"), "model", 2, 3, 1000, 1, List.of(), true,
                null, null, 0));
        OfferEligibilityService eligibility = mock(OfferEligibilityService.class);
        when(eligibility.evaluate(any(), any(), any())).thenReturn(new OfferEligibilityService.Eligibility(
                "AT00000002", "AT00000001", true, List.of(), List.of(ItemCondition.NEW), ORG_B));
        when(products.findByVariantGtin(any())).thenReturn(Optional.of(pageOfB));
        CatalogMatchServiceImpl match = new CatalogMatchServiceImpl(products, mock(CategoryRepository.class),
                requests, eligibility, settings, mock(MatchMetrics.class), tenants.resolver());
        match.match(new CatalogMatchService.MatchCommand("012345678905", ORG_B));
        ArgumentCaptor<String> org = ArgumentCaptor.forClass(String.class);
        verify(eligibility).evaluate(any(), any(), org.capture());
        assertThat(org.getValue()).isEqualTo(ORG_A);
    }

    @Test
    void aSellerCannotPrintUnitLabelsForAnotherOrganisation() {
        Product live = new Product(pageOfB.id(), pageOfB.atin(), pageOfB.owner(), null, null, "custom", "Ankara tote",
                Map.of(), List.of(), "", pageOfB.variants(), ProductStatus.LIVE, null, NOW, NOW, 0);
        when(products.findByVariantAtin(live.variants().getFirst().atin())).thenReturn(Optional.of(live));
        LabelServiceImpl labels = new LabelServiceImpl(mock(LabelPolicy.class), List.of(), products,
                mock(UnitLabelRepository.class), mock(IdentifierIssuer.class), mock(LabelSettings.class),
                tenants.resolver(), CLOCK);
        assertThatThrownBy(() -> labels.unitLabel("AT00000002", ORG_B, LabelFormat.SVG, 1))
                .isInstanceOf(ForbiddenActionException.class);
        tenants.as(TenantFixture.sellerOf(ORG_A, "catalog:write"));
        Product draft = new Product(pageOfB.id(), pageOfB.atin(), pageOfB.owner(), null, null, "custom", "Ankara tote",
                Map.of(), List.of(), "", pageOfB.variants(), ProductStatus.IN_REVIEW, null, NOW, NOW, 0);
        when(products.findByVariantAtin(draft.variants().getFirst().atin())).thenReturn(Optional.of(draft));
        CrossTenant.assertDenied(() -> labels.unitLabel("AT00000002", null, LabelFormat.SVG, 1));
    }

    // ------------------------------------------------------------------ customer reviews (CUSTOMER_SELF)

    private ProductReviewServiceImpl reviewService() {
        Product live = new Product(pageOfB.id(), pageOfB.atin(), pageOfB.owner(), null, null, "custom", "Ankara tote",
                Map.of(), List.of(), "", pageOfB.variants(), ProductStatus.LIVE, null, NOW, NOW, 0);
        when(products.findByAtin(live.atin())).thenReturn(Optional.of(live));
        when(reviews.findLive(any(), any())).thenReturn(Optional.empty());
        return new ProductReviewServiceImpl(products, mock(ListingSuppressionRepository.class), reviews,
                mock(PurchaseVerifier.class), mock(ReviewContentPolicy.class), mock(ReviewSettings.class),
                mock(ReviewEvents.class), tenants.resolver(), tenants.access(), CLOCK);
    }

    @Test
    void reviewsAreReadAndChangedForTheTokenSubjectOnly() {
        ProductReviewServiceImpl service = reviewService();
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        service.mine("AT00000001");
        verify(reviews).findLive(pageOfB.id(), TenantFixture.CUSTOMER_1);
        assertThatThrownBy(() -> service.delete("AT00000001")).isInstanceOf(NotFoundException.class);
        verify(reviews, never()).findLive(pageOfB.id(), TenantFixture.CUSTOMER_2);
        verify(reviews, never()).update(any());
    }

    @Test
    void orgMembersAndStaffCannotWriteEditOrDeleteCustomerReviews() {
        ProductReviewServiceImpl service = reviewService();
        tenants.as(TenantFixture.sellerOf(ORG_B, "catalog:write"));
        assertThatThrownBy(() -> service.mine("AT00000001")).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> service.edit("AT00000001", null)).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> service.delete("AT00000001")).isInstanceOf(ForbiddenActionException.class);
        tenants.as(TenantFixture.staff("catalog:moderate"));
        assertThatThrownBy(() -> service.delete("AT00000001")).isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void onlyTheProductsOwnSellerCanReportAsASeller() {
        ProductReviewServiceImpl service = reviewService();
        when(reviewOfB.published()).thenReturn(true);
        tenants.as(TenantFixture.sellerOf(ORG_A, "catalog:write"));
        assertThatThrownBy(() -> service.report(reviewOfB.id(), ReviewReasonCode.OTHER, null))
                .isInstanceOf(ForbiddenActionException.class);
        verify(reviews, never()).addReport(any());
    }

    // ------------------------------------------------------------------ reviews (seller side)

    @Test
    void aSellerCannotReplyToAnotherSellersReview() {
        CrossTenant.assertDenied(() -> sellerReviews.reply(reviewOfB.id(), "thanks"));
        verify(reviews, never()).update(any());
    }

    @Test
    void sellerReviewListIgnoresAnOrgNamedByASeller() {
        when(reviews.publishedForOwner(any(), anyBoolean(),
                anyInt(), anyInt()))
                .thenReturn(new ProductReviewRepository.Page(List.of(), 0, 0, 25));
        sellerReviews.list(ORG_B, false, 0, 25);
        verify(reviews).publishedForOwner(eq(ORG_A),
                anyBoolean(), anyInt(),
                anyInt());
    }
}
