package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.catalog.application.PageVariantService.Added;
import com.afrotransact.catalog.application.PageVariantService.Moved;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.GtinAlreadyUsed;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.sizing.SizingFixtures;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.TaxResolver;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * S385: a new variant joins a page, a variant moves to another page or is split onto a page of its own. The tax gate keeps its
 * rules: a page on sale only takes a variant that has no decision when staff accept that it leaves sale, and a split page is
 * never live.
 */
class PageVariantServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final String TAX_PROFILE = "AT.APPAREL";
    private static final String UPC = "012345678905";

    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ProductLiveGate liveGate = mock(ProductLiveGate.class);
    private final ImageGate imageGate = mock(ImageGate.class);
    private final CatalogSettingsService settings = mock(CatalogSettingsService.class);
    private final ProductEvents productEvents = mock(ProductEvents.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final UUID dressNode = UUID.randomUUID();
    private final UUID otherNode = UUID.randomUUID();
    private PageVariantServiceImpl service;

    @BeforeEach
    void setUp() {
        AtomicInteger atins = new AtomicInteger(40);
        IdentifierIssuer ids = mock(IdentifierIssuer.class);
        when(ids.nextAtin()).thenAnswer(i -> new Atin(String.format("AT%08d", atins.incrementAndGet())));
        when(categories.findById(dressNode)).thenReturn(Optional.of(node(dressNode, TAX_PROFILE)));
        when(categories.findById(otherNode)).thenReturn(Optional.of(node(otherNode, "AT.JEWELLERY")));
        when(categories.findProductType("dress")).thenReturn(Optional.of(SizingFixtures.DRESS));
        when(liveGate.closeIfNotReady(any(), any())).thenAnswer(i -> i.getArgument(0));
        when(products.updateWithVariants(any())).thenAnswer(i -> i.getArgument(0));
        when(products.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(settings.current()).thenReturn(new CatalogPolicy(List.of("02"), "mpn", 2, 3, 200, 3, List.of(), true, null, null, 0));
        when(imageGate.check(any(), any(), anyString(), any())).thenReturn(new ImageGate.Outcome(Map.of(), List.of()));
        TaxGateEvaluator taxGate = new TaxGateEvaluator(request -> new TaxResolver.Resolution("RESOLVED", "EXEMPT", "AT.X",
                "S001", "0%", null, "v", "r"), false);
        service = new PageVariantServiceImpl(products, categories, ids, taxGate, liveGate, imageGate, settings, productEvents,
                events);
    }

    // ---- NEW_VARIANT ----------------------------------------------------------------------------------------------

    @Test
    void anApprovedNewVariantIsAddedAsAChildAtinOfThePageWithTheOwnersGeneratedSku() {
        Product page = page(ProductStatus.IN_REVIEW, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")));

        Added added = service.addVariant(newVariant(page, Map.of("Size", "XL"), "4006381333931"), page, false, NOW);

        assertThat(added.atin()).isEqualTo("AT00000041");
        assertThat(added.page().variants()).hasSize(2);
        Variant created = added.page().variants().getLast();
        assertThat(created.atin().value()).isEqualTo("AT00000041");
        assertThat(created.options()).containsExactly(Map.entry("Size", "XL"));
        assertThat(created.sellerSku()).endsWith("-AT00000041");
        assertThat(created.sellerSkuGenerated()).isTrue();
        assertThat(created.quantity()).as("stock belongs to offers").isZero();
        assertThat(created.priceMinor()).isEqualTo(page.variants().getFirst().priceMinor());
        assertThat(added.page().status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(added.page().updatedAt()).isEqualTo(NOW);
        verify(productEvents).publish(eq(CatalogEvents.PRODUCT_UPDATED), any(Product.class));
        verify(productEvents).publishTaxFactsChanged(eq(added.page()), any(), any(), eq(page.variants()), any());
    }

    @Test
    void aPageOnSaleDoesNotTakeAVariantWithoutATaxDecisionUnlessStaffAcceptTheHold() {
        Product page = page(ProductStatus.LIVE, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")));

        assertThatThrownBy(() -> service.addVariant(newVariant(page, Map.of("Size", "XL"), null), page, false, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-needs-tax-review"));
        verify(products, never()).updateWithVariants(any());
        verify(liveGate, never()).closeIfNotReady(any(), any());
    }

    @Test
    void withTheHoldAcceptedTheLiveGateIsTheOnlyThingThatDecidesWhetherThePageStaysOnSale() {
        Product page = page(ProductStatus.LIVE, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")));
        Product reopened = page.holdForTax(NOW);
        when(liveGate.closeIfNotReady(any(), any())).thenReturn(reopened);

        Added added = service.addVariant(newVariant(page, Map.of("Size", "XL"), "4006381333931"), page, true, NOW);

        verify(liveGate).closeIfNotReady(any(Product.class), eq(NOW));
        assertThat(added.page().status()).as("the gate closed it").isEqualTo(ProductStatus.IN_REVIEW);
    }

    @Test
    void aLivePageTheGateKeepsLiveIsReconciledAgainstTaxReturns() {
        Product page = page(ProductStatus.LIVE, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")));
        Product closed = page.holdForTax(NOW);
        when(liveGate.reconcile(page.id())).thenReturn(closed);

        Added added = service.addVariant(newVariant(page, Map.of("Size", "XL"), "4006381333931"), page, true, NOW);

        verify(liveGate).reconcile(page.id());
        assertThat(added.page().status()).isEqualTo(ProductStatus.IN_REVIEW);
    }

    @Test
    void optionsThePageAlreadyHasOrDoesNotUseAreRefused() {
        Product page = page(ProductStatus.IN_REVIEW, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")));

        assertThatThrownBy(() -> service.addVariant(newVariant(page, Map.of("Size", "m"), "4006381333931"), page, false, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getMessage())
                        .contains("already has a variant with these options"));
        assertThatThrownBy(() -> service.addVariant(newVariant(page, Map.of("Color", "Gold"), "4006381333931"), page, false,
                NOW)).isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getMessage())
                .contains("Give a value for each of Size"));
        verify(products, never()).updateWithVariants(any());
    }

    @Test
    void aPageWithoutAVariationThemeHasNothingToAddTo() {
        Product page = pageWithTheme("", ProductStatus.IN_REVIEW, variant("AT10000002", UPC, Map.of()));

        assertThatThrownBy(() -> service.addVariant(newVariant(page, Map.of("Size", "XL"), "4006381333931"), page, false, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-variant-invalid"));
    }

    @Test
    void thePagesBarcodeRulesApplyToTheNewVariant() {
        Product withBarcodes = page(ProductStatus.IN_REVIEW, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")));

        assertThatThrownBy(() -> service.addVariant(newVariant(withBarcodes, Map.of("Size", "XL"), null), withBarcodes, false,
                NOW)).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.getMessage()).contains("Give the barcode"));
        assertThatThrownBy(() -> service.addVariant(newVariant(withBarcodes, Map.of("Size", "XL"), "012345678900"),
                withBarcodes, false, NOW)).isInstanceOf(BusinessRuleException.class);
        // A restricted prefix is never a product barcode.
        assertThatThrownBy(() -> service.addVariant(newVariant(withBarcodes, Map.of("Size", "XL"), "020000000009"),
                withBarcodes, false, NOW)).isInstanceOf(BusinessRuleException.class);
        verify(products, never()).updateWithVariants(any());
    }

    @Test
    void aBarcodeAnotherPageHasIsAConflictNamingThatPage() {
        Product page = page(ProductStatus.IN_REVIEW, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")));
        Product other = pageWithTheme("Size", ProductStatus.LIVE, variant("AT50000002", "4006381333931", Map.of()));
        when(products.findByVariantGtin("4006381333931")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.addVariant(newVariant(page, Map.of("Size", "XL"), "4006381333931"), page, false, NOW))
                .isInstanceOfSatisfying(GtinAlreadyUsed.class, e -> assertThat(e.existingAtin()).isEqualTo(other.atin().value()));
    }

    @Test
    void aPageWhoseVariantsHaveNoBarcodeTakesANoBarcodeVariant() {
        Product page = page(ProductStatus.IN_REVIEW, dressNode, variant("AT10000002", null, Map.of("Size", "M")));
        ProductChangeRequest r = request(page, Map.of("options", Map.of("Size", "XL"), "gtinExempt", "true"));

        Added added = service.addVariant(r, page, false, NOW);

        assertThat(added.page().variants().getLast().gtin()).isNull();
        assertThat(added.page().variants().getLast().gtinExempt()).isTrue();
    }

    @Test
    void thePicturesOfTheNewVariantAreJudgedByMediaLikeAnyOtherPicture() {
        Product page = page(ProductStatus.IN_REVIEW, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")));
        when(imageGate.check(any(), any(), anyString(), any())).thenReturn(new ImageGate.Outcome(
                Map.of("variants[0].images", "This photo is too small."), List.of()));
        Map<String, Object> payload = payload(Map.of("Size", "XL"), "4006381333931");
        payload.put("images", List.of(Map.of("url", "https://cdn.example/xl.jpg", "width", 500, "height", 500, "bytes", 40_000,
                "contentType", "image/jpeg")));

        assertThatThrownBy(() -> service.addVariant(request(page, payload), page, false, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-image-rejected"));
        verify(products, never()).updateWithVariants(any());
    }

    // ---- MOVE_VARIANT ---------------------------------------------------------------------------------------------

    @Test
    void aMovedVariantKeepsItsAtinLeavesTheSourceAndTheOffersAreToldToFollow() {
        Product source = page(ProductStatus.LIVE, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")),
                variant("AT10000003", "4006381333931", Map.of("Size", "L")));
        Product target = page(ProductStatus.IN_REVIEW, dressNode, variant("AT20000002", "5901234123457", Map.of("Size", "S")));
        UUID request = UUID.randomUUID();

        Moved moved = service.moveVariant(source, "AT10000003", target, false, "staff_1", "It is a size of the other dress",
                request, NOW);

        assertThat(moved.variantAtin()).isEqualTo("AT10000003");
        assertThat(moved.source().variants()).extracting(v -> v.atin().value()).containsExactly("AT10000002");
        assertThat(moved.target().variants()).extracting(v -> v.atin().value()).containsExactly("AT20000002", "AT10000003");
        assertThat(moved.source().status()).isEqualTo(ProductStatus.LIVE);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(CatalogEvents.VARIANT_MOVED), eq(CatalogEvents.AGGREGATE_PRODUCT),
                eq(target.id().toString()), payload.capture());
        assertThat(payload.getValue()).containsEntry("kind", "MOVE").containsEntry("variantAtin", "AT10000003")
                .containsEntry("fromAtin", source.atin().value()).containsEntry("toAtin", target.atin().value())
                .containsEntry("toProductId", target.id().toString()).containsEntry("toStatus", "IN_REVIEW")
                .containsEntry("toCategoryId", dressNode.toString()).containsEntry("movedBy", "staff_1")
                .containsEntry("changeRequestId", request.toString());
    }

    @Test
    void theVariantIsTakenOffTheSourceBeforeItJoinsTheTargetSoItsBarcodeIsFreeWhenItIsClaimedAgain() {
        Product source = page(ProductStatus.IN_REVIEW, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")),
                variant("AT10000003", "4006381333931", Map.of("Size", "L")));
        Product target = page(ProductStatus.IN_REVIEW, dressNode, variant("AT20000002", "5901234123457", Map.of("Size", "S")));

        service.moveVariant(source, "AT10000003", target, false, "staff_1", "Wrong page", null, NOW);

        ArgumentCaptor<Product> writes = ArgumentCaptor.forClass(Product.class);
        verify(products, times(2)).updateWithVariants(writes.capture());
        assertThat(writes.getAllValues().get(0).id()).isEqualTo(source.id());
        assertThat(writes.getAllValues().get(1).id()).isEqualTo(target.id());
    }

    @Test
    void aTargetOnSaleWithTheSameTaxProfileNeedsNoHold() {
        Product source = page(ProductStatus.LIVE, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")),
                variant("AT10000003", "4006381333931", Map.of("Size", "L")));
        Product target = page(ProductStatus.LIVE, dressNode, variant("AT20000002", "5901234123457", Map.of("Size", "S")));

        Moved moved = service.moveVariant(source, "AT10000003", target, false, "staff_1", "Wrong page", null, NOW);

        assertThat(moved.target().variants()).hasSize(2);
        verify(liveGate).closeIfNotReady(any(Product.class), eq(NOW));
    }

    @Test
    void aTargetOnSaleTaxedDifferentlyNeedsStaffToAcceptTheHold() {
        Product source = page(ProductStatus.IN_REVIEW, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")),
                variant("AT10000003", "4006381333931", Map.of("Size", "L")));
        Product target = page(ProductStatus.LIVE, otherNode, variant("AT20000002", "5901234123457", Map.of("Size", "S")));

        assertThatThrownBy(() -> service.moveVariant(source, "AT10000003", target, false, "staff_1", "Wrong page", null, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-needs-tax-review"));
        verify(products, never()).updateWithVariants(any());

        Moved accepted = service.moveVariant(source, "AT10000003", target, true, "staff_1", "Wrong page", null, NOW);
        assertThat(accepted.target().variants()).hasSize(2);
    }

    @Test
    void thePagesCannotBeLeftWithoutVariantsOrMergedByMoving() {
        Product single = page(ProductStatus.LIVE, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")));
        Product target = page(ProductStatus.LIVE, dressNode, variant("AT20000002", "5901234123457", Map.of("Size", "S")));

        assertThatThrownBy(() -> service.moveVariant(single, "AT10000002", target, false, "s", "Wrong page", null, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getMessage()).contains("Merge the two pages"));
        assertThatThrownBy(() -> service.moveVariant(target, "AT20000002", target, false, "s", "Wrong page", null, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getMessage()).contains("already on that page"));
    }

    @Test
    void aMoveToAPageThatHasTheSameOptionsOrWasMergedOrRejectedIsRefused() {
        Product source = page(ProductStatus.LIVE, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")),
                variant("AT10000003", "4006381333931", Map.of("Size", "L")));
        Product sameOptions = page(ProductStatus.LIVE, dressNode, variant("AT20000002", "5901234123457", Map.of("Size", "l")));
        Product merged = page(ProductStatus.MERGED, dressNode, variant("AT30000002", null, Map.of("Size", "S")));
        Product rejected = page(ProductStatus.REJECTED, dressNode, variant("AT40000002", null, Map.of("Size", "S")));

        for (Product target : List.of(sameOptions, merged, rejected)) {
            assertThatThrownBy(() -> service.moveVariant(source, "AT10000003", target, true, "s", "Wrong page", null, NOW))
                    .isInstanceOfSatisfying(BusinessRuleException.class,
                            e -> assertThat(e.code()).isEqualTo("catalog.change-request-variant-invalid"));
        }
        verify(products, never()).updateWithVariants(any());
    }

    @Test
    void aVariantThatIsNotOnThePageCannotBeMoved() {
        Product source = page(ProductStatus.LIVE, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")),
                variant("AT10000003", "4006381333931", Map.of("Size", "L")));
        Product target = page(ProductStatus.LIVE, dressNode, variant("AT20000002", "5901234123457", Map.of("Size", "S")));

        assertThatThrownBy(() -> service.moveVariant(source, "AT99999999", target, true, "s", "Wrong page", null, NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ---- SPLIT_VARIANT --------------------------------------------------------------------------------------------

    @Test
    void aSplitVariantBecomesTheOnlyVariantOfANewPageInReviewWithTheSameOwnerAndAtin() {
        Product source = page(ProductStatus.LIVE, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")),
                variant("AT10000003", "4006381333931", Map.of("Size", "L")));

        Moved split = service.splitVariant(source, "AT10000003", "staff_1", "A different dress", UUID.randomUUID(), NOW);

        Product page = split.target();
        assertThat(page.status()).as("a split page starts in review, never live").isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(page.id()).isNotEqualTo(source.id());
        assertThat(page.atin().value()).isEqualTo("AT00000041");
        assertThat(page.owner()).isEqualTo(source.owner());
        assertThat(page.categoryId()).isEqualTo(dressNode);
        assertThat(page.variants()).singleElement().satisfies(v -> assertThat(v.atin().value()).isEqualTo("AT10000003"));
        assertThat(split.source().variants()).extracting(v -> v.atin().value()).containsExactly("AT10000002");
        verify(products).insert(page);
        verify(liveGate, never()).approve(any(), any());
        verify(liveGate, never()).resume(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(CatalogEvents.VARIANT_MOVED), eq(CatalogEvents.AGGREGATE_PRODUCT), eq(page.id().toString()),
                payload.capture());
        assertThat(payload.getValue()).containsEntry("kind", "SPLIT").containsEntry("toAtin", "AT00000041")
                .containsEntry("toStatus", "IN_REVIEW");
    }

    @Test
    void thereIsNothingToSplitOffAPageWithOneVariantOrNoCategory() {
        Product single = page(ProductStatus.LIVE, dressNode, variant("AT10000002", UPC, Map.of("Size", "M")));
        Product noCategory = page(ProductStatus.IN_REVIEW, null, variant("AT10000002", UPC, Map.of("Size", "M")),
                variant("AT10000003", "4006381333931", Map.of("Size", "L")));

        assertThatThrownBy(() -> service.splitVariant(single, "AT10000002", "s", "Different", null, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.splitVariant(noCategory, "AT10000003", "s", "Different", null, NOW))
                .isInstanceOf(BusinessRuleException.class);
        verify(products, never()).insert(any());
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private static CategoryNode node(UUID id, String taxProfile) {
        return new CategoryNode(id, null, "dresses", "Dresses", CategoryLevel.PRODUCT_TYPE, "dress", "Fashion › Dresses", 0,
                true, 0, null, null, Set.of(), taxProfile, null);
    }

    private static Variant variant(String atin, String gtin, Map<String, String> options) {
        return new Variant(UUID.randomUUID(), new Atin(atin), "S-" + atin, gtin, gtin == null, options, 4900, "USD", 5);
    }

    private static Product page(ProductStatus status, UUID category, Variant... variants) {
        return build("Size", status, category, variants);
    }

    private Product pageWithTheme(String theme, ProductStatus status, Variant... variants) {
        return build(theme, status, dressNode, variants);
    }

    private static Product build(String theme, ProductStatus status, UUID category, Variant... variants) {
        return new Product(UUID.randomUUID(), new Atin("AT" + variants[0].atin().value().substring(2, 3) + "0000001"),
                new ListingOwner(ListingOwnerKind.SELLER, "org_a"), category, null, "dress", "Ankara Wrap Dress",
                Map.of("brand", "Adire Lagos"), List.of(), theme, List.of(variants), status, null, NOW.minusSeconds(900),
                NOW.minusSeconds(900), 2);
    }

    private static Map<String, Object> payload(Map<String, String> options, String gtin) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("options", new LinkedHashMap<>(options));
        if (gtin != null) {
            payload.put("gtin", gtin);
        }
        return payload;
    }

    private static ProductChangeRequest request(Product page, Map<String, Object> payload) {
        return ProductChangeRequest.filed(page.id(), page.atin().value(), null, null, ChangeRequestType.NEW_VARIANT, "", payload,
                null, "org_b", "user_b", NOW.minusSeconds(3600), NOW.plusSeconds(86_400));
    }

    private static ProductChangeRequest newVariant(Product page, Map<String, String> options, String gtin) {
        return request(page, payload(options, gtin));
    }
}
