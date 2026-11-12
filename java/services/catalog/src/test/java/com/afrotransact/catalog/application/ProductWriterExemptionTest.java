package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.catalog.application.ImageSettings.Mode;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.DefaultProductDraftPolicy;
import com.afrotransact.catalog.domain.ExemptionRequirement;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.PageAlreadyExists;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.sizing.DefaultSizeChartPolicy;
import com.afrotransact.catalog.domain.sizing.SizingFixtures;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.ImageVerifier;
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SizeChartRepository;
import com.afrotransact.catalog.port.TaxResolver;
import com.afrotransact.catalog.testsupport.RulesFixtures;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;

/**
 * S387: the product writer assesses an item without a barcode. In a brand-gated category, or for a brand that carries
 * barcodes elsewhere, the page is created as PENDING_EXEMPTION (never live) with a request for staff, and the seller reads
 * the plain note. S385: a barcode under a wrong-match report cannot get a second page.
 */
class ProductWriterExemptionTest {

    private static final ListingOwner SELLER = new ListingOwner(ListingOwnerKind.SELLER, SizingFixtures.OWNER);
    private static final String UPC = "012345678905";

    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final NewPageGuard newPages = mock(NewPageGuard.class);
    private final ProductEvents events = mock(ProductEvents.class);
    private final ProductLiveGate liveGate = mock(ProductLiveGate.class, AdditionalAnswers.returnsFirstArg());
    private final UUID node = UUID.randomUUID();
    private ProductWriterImpl writer;

    @BeforeEach
    void setUp() {
        AtomicInteger atins = new AtomicInteger();
        IdentifierIssuer ids = mock(IdentifierIssuer.class);
        when(ids.nextAtin()).thenAnswer(i -> new Atin(String.format("AT%08d", atins.incrementAndGet())));
        when(categories.findById(node)).thenReturn(Optional.of(new CategoryNode(node, null, "dresses", "Dresses",
                CategoryLevel.PRODUCT_TYPE, "dress", "Fashion › Women › Dresses", 0, true, 0, null, null)));
        when(categories.findProductType("dress")).thenReturn(Optional.of(SizingFixtures.DRESS));
        when(products.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(newPages.assessExemption(any(), anyString(), any(), anyBoolean())).thenReturn(Optional.empty());
        BrandResolver brands = mock(BrandResolver.class);
        when(brands.resolve(any(), any(), any(), any(), any())).thenAnswer(i -> i.getArgument(3));
        ImageGate gate = new ImageGateImpl(mock(ImageVerifier.class),
                new ImageSettings(Mode.OFF, 60, false, 50, Duration.ofSeconds(30), Duration.ofDays(30)));
        writer = new ProductWriterImpl(products, categories, mock(CategoryRequestRepository.class),
                mock(ProductDraftRepository.class), ids, new DefaultProductDraftPolicy(), new DefaultSizeChartPolicy(),
                mock(SizeChartRepository.class), events, brands, new UnrestrictedScopeGuard(),
                new TaxGateEvaluator(request -> new TaxResolver.Resolution("RESOLVED", "EXEMPT", "AT.X", "S001", "0%",
                        null, "v", "r"), false),
                RulesFixtures.permissiveService(), liveGate, newPages,
                Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC), gate);
    }

    private CreateProductCommand create(VariantDraft... variants) {
        return create("", variants);
    }

    private CreateProductCommand create(String theme, VariantDraft... variants) {
        return new CreateProductCommand(node, null, "Ankara Wrap Dress, Puff Sleeve", Map.of("brand", "Adire Lagos"),
                List.of(new ProductImage("https://cdn/main.jpg", 1600, 1600, 200_000, "image/jpeg")), theme,
                List.of(variants), "USD");
    }

    private static VariantDraft withoutBarcode(String sku) {
        return new VariantDraft(Map.of(), sku, "", true, 4900, 5);
    }

    private static VariantDraft withBarcode(String sku, String gtin) {
        return new VariantDraft(Map.of(), sku, gtin, false, 4900, 5);
    }

    @Test
    void aNoBarcodeItemThatNeedsStaffIsPendingExemptionNotLiveWithThePlainNote() {
        when(newPages.assessExemption(any(), eq("dress"), any(), eq(true)))
                .thenReturn(Optional.of(ExemptionRequirement.BRAND_GATED_CATEGORY));

        Product created = writer.create(SELLER, create(withoutBarcode("A-1")));

        assertThat(created.status()).isEqualTo(ProductStatus.PENDING_EXEMPTION);
        assertThat(created.moderationNote()).isEqualTo("We are checking that this item has no barcode.");
        verify(newPages).exemptionRequested(eq(created), any(), eq(ExemptionRequirement.BRAND_GATED_CATEGORY));
        verify(liveGate, never()).approve(any(), any());
        verify(events).publish(CatalogEvents.PRODUCT_CREATED, created);
    }

    @Test
    void aKnownGs1BrandWithoutABarcodeAlsoWaitsForStaff() {
        when(newPages.assessExemption(any(), anyString(), any(), anyBoolean()))
                .thenReturn(Optional.of(ExemptionRequirement.KNOWN_GS1_BRAND));

        Product created = writer.create(SELLER, create(withoutBarcode("A-1")));

        assertThat(created.status()).isEqualTo(ProductStatus.PENDING_EXEMPTION);
        verify(newPages).exemptionRequested(eq(created), any(), eq(ExemptionRequirement.KNOWN_GS1_BRAND));
    }

    @Test
    void anOpenCategoryGetsItsAtinAtOnceAndGoesToNormalReview() {
        Product created = writer.create(SELLER, create(withoutBarcode("A-1")));

        assertThat(created.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(created.moderationNote()).isNull();
        assertThat(created.atin().value()).startsWith("AT");
        verify(newPages, never()).exemptionRequested(any(), any(), any());
    }

    @Test
    void theBrandPlusPartNumberMatchComesFirstAndNothingIsStored() {
        when(newPages.assessExemption(any(), anyString(), any(), anyBoolean())).thenThrow(new PageAlreadyExists("AT10000001"));

        assertThatThrownBy(() -> writer.create(SELLER, create(withoutBarcode("A-1"))))
                .isInstanceOfSatisfying(PageAlreadyExists.class, e -> assertThat(e.existingAtin()).isEqualTo("AT10000001"));
        verify(products, never()).insert(any());
        verify(events, never()).publish(eq(CatalogEvents.PRODUCT_CREATED), any(Product.class));
    }

    @Test
    void aListingWhereEveryVariantHasABarcodeAsksTheGuardNoQuestionsAboutExemption() {
        writer.create(SELLER, create(withBarcode("A-1", UPC)));

        verify(newPages).assessExemption(any(), eq("dress"), any(), eq(false));
        verify(newPages).refuseWhileWrongMatchOpen(UPC);
    }

    @Test
    void aMixedListingIsAssessedBecauseOneVariantHasNoBarcode() {
        writer.create(SELLER, create("Size", new VariantDraft(Map.of("Size", "S"), "A-1", UPC, false, 4900, 5),
                new VariantDraft(Map.of("Size", "M"), "A-2", "", true, 4900, 5)));

        verify(newPages).assessExemption(any(), eq("dress"), any(), eq(true));
    }

    @Test
    void aBarcodeUnderAWrongMatchReportCannotGetASecondPage() {
        doThrow(new ConflictException("catalog.wrong-match-open", "We are checking this barcode. We will tell you within 2 "
                + "business days.")).when(newPages).refuseWhileWrongMatchOpen(UPC);

        assertThatThrownBy(() -> writer.create(SELLER, create(withBarcode("A-1", UPC))))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.code()).isEqualTo("catalog.wrong-match-open"));
        verify(products, never()).insert(any());
    }

    @Test
    void aNoBarcodeVariantIsNotCheckedAgainstWrongMatchReports() {
        writer.create(SELLER, create(withoutBarcode("A-1")));

        verify(newPages, never()).refuseWhileWrongMatchOpen(anyString());
    }
}
