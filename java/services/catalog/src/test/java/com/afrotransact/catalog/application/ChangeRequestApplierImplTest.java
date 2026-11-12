package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.application.ImageSettings.Mode;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.AttributeSection;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.brand.BrandNames;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import com.afrotransact.catalog.domain.images.ImageCheckResult;
import com.afrotransact.catalog.domain.images.ImageFinding;
import com.afrotransact.catalog.port.BrandRepository;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageVerifier;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * S385: what an approved content request does to a page, and what it refuses. The image requests go through media's verdict
 * (S390): never the width and height the seller sent, a held picture waits for the image review queue.
 */
class ChangeRequestApplierImplTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final UUID TAX_PROFILE_A = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final String MAIN = "https://cdn.example/main.jpg";

    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final BrandRepository brands = mock(BrandRepository.class);
    private final ProductEvents events = mock(ProductEvents.class);
    private final Map<String, ImageCheckResult> media = new LinkedHashMap<>();
    private final List<ImageVerifier.Item> asked = new ArrayList<>();
    private boolean mediaDown;
    private Mode mode = Mode.ENFORCE;
    private Product page;
    private ChangeRequestApplierImpl applier;

    @BeforeEach
    void setUp() {
        AttributeDefinition material = new AttributeDefinition("material", "Material", List.of(), false, false);
        AttributeDefinition blend = new AttributeDefinition("fabric_blend", "Fabric blend", List.of(), false, false,
                AttributeKind.TEXT, List.of(), AttributeSection.DETAILS, null, true, null, true, "VARIANT", null, null,
                false, null);
        ProductType type = new ProductType("custom", "Custom", List.of(material, blend), List.of(), IdentifierRule.GTIN);
        when(categories.findProductType("custom")).thenReturn(Optional.of(type));
        when(categories.findById(CATEGORY)).thenReturn(Optional.of(node(CATEGORY, TAX_PROFILE_A, "custom", true)));
        when(products.update(any())).thenAnswer(i -> i.getArgument(0));
        page = page(List.of(image("https://cdn.example/old-main.jpg"), image("https://cdn.example/old-side.jpg"),
                image("https://cdn.example/label.jpg").asLabel()));
        applier = newApplier();
    }

    private ChangeRequestApplierImpl newApplier() {
        ImageVerifier verifier = new ImageVerifier() {
            @Override
            public List<ImageCheckResult> verify(List<Item> items) {
                if (mediaDown) {
                    throw new ImageServiceUnavailable("media is down", null);
                }
                asked.addAll(items);
                return items.stream().map(i -> media.getOrDefault(i.url(), ImageCheckResult.unknown(i.url()))).toList();
            }

            @Override
            public List<ImageCheckResult> rescore(List<Item> items) {
                return verify(items);
            }
        };
        return new ChangeRequestApplierImpl(products, categories, brands, events, verifier,
                new ImageSettings(mode, 60, false, 50, Duration.ofSeconds(30), Duration.ofDays(30)));
    }

    // ---- text and details -----------------------------------------------------------------------------------------

    @Test
    void aTitleChangeIsStoredAndPublishedAndNeverTouchesTheStatus() {
        Product saved = applier.apply(request(ChangeRequestType.TITLE, "", Map.of("value", "Ankara print tote, large")), page,
                NOW);

        assertThat(saved.title()).isEqualTo("Ankara print tote, large");
        assertThat(saved.status()).as("a content change never moves a page in or out of sale").isEqualTo(ProductStatus.LIVE);
        assertThat(saved.updatedAt()).isEqualTo(NOW);
        verify(products).update(saved);
        verify(events).publish(CatalogEvents.PRODUCT_UPDATED, saved);
    }

    @Test
    void aDescriptionChangeKeepsTheOtherDetails() {
        Product saved = applier.apply(request(ChangeRequestType.DESCRIPTION, "", Map.of("value", "Hand-stitched in Lagos")),
                page, NOW);

        assertThat(saved.details().description()).isEqualTo("Hand-stitched in Lagos");
        assertThat(saved.details().condition()).isEqualTo(page.details().condition());
        assertThat(saved.title()).isEqualTo(page.title());
    }

    @Test
    void aDetailThatIsNotATaxFactIsSetAndTheOthersStay() {
        Product saved = applier.apply(request(ChangeRequestType.ATTRIBUTE, "material", Map.of("key", "material", "value",
                "Cotton")), page, NOW);

        assertThat(saved.attributes()).containsEntry("material", "Cotton").containsEntry(BrandKeys.NAME, "Adire Lagos");
    }

    @Test
    void aDetailThatDecidesTheTaxIsRefusedAndGoesToTaxReview() {
        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.ATTRIBUTE, "fabric_blend",
                Map.of("key", "fabric_blend", "value", "Silk")), page, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-needs-tax-review"));
        verify(products, never()).update(any());
    }

    @Test
    void aDetailTheKindOfProductDoesNotHaveIsRefused() {
        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.ATTRIBUTE, "colourway",
                Map.of("key", "colourway", "value", "Red")), page, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-unknown-detail"));
    }

    @Test
    void theBrandIsChangedWithABrandRequestNotAsADetail() {
        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.ATTRIBUTE, BrandKeys.NAME,
                Map.of("key", BrandKeys.NAME, "value", "Other")), page, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-wrong-type"));
    }

    // ---- brand and category ---------------------------------------------------------------------------------------

    @Test
    void anApprovedBrandReplacesTheBrandAndItsId() {
        Brand brand = Brand.create("Kente Co", BrandStatus.APPROVED, "org_x", Set.of(), NOW);
        when(brands.findLiveByKey(BrandNames.normalise("Kente Co"))).thenReturn(List.of(brand));

        Product saved = applier.apply(request(ChangeRequestType.BRAND, "", Map.of("value", "Kente Co")), page, NOW);

        assertThat(saved.attributes()).containsEntry(BrandKeys.NAME, "Kente Co")
                .containsEntry(BrandKeys.ID, brand.id().toString()).doesNotContainKey(BrandKeys.REASON);
    }

    @Test
    void aBrandThatIsNotApprovedYetIsRefused() {
        Brand pending = Brand.create("Kente Co", BrandStatus.PENDING, "org_x", Set.of(), NOW);
        when(brands.findLiveByKey(BrandNames.normalise("Kente Co"))).thenReturn(List.of(pending));

        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.BRAND, "", Map.of("value", "Kente Co")), page, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-brand-unknown"));
    }

    @Test
    void aCategoryWithTheSameTaxProfileIsAccepted() {
        UUID sibling = UUID.randomUUID();
        when(categories.findById(sibling)).thenReturn(Optional.of(node(sibling, TAX_PROFILE_A, "tote-bags", true)));

        Product saved = applier.apply(request(ChangeRequestType.CATEGORY, "", Map.of("categoryId", sibling.toString())),
                page, NOW);

        assertThat(saved.categoryId()).isEqualTo(sibling);
        assertThat(saved.status()).isEqualTo(ProductStatus.LIVE);
    }

    @Test
    void aCategoryTaxedDifferentlyGoesToTaxReviewInsteadOfAPageEdit() {
        UUID taxedOther = UUID.randomUUID();
        when(categories.findById(taxedOther)).thenReturn(Optional.of(node(taxedOther, UUID.randomUUID(), "jewellery", true)));

        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.CATEGORY, "",
                Map.of("categoryId", taxedOther.toString())), page, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-needs-tax-review"));
        verify(products, never()).update(any());
    }

    @Test
    void aCategoryThatIsRetiredOrNotAProductTypeIsRefused() {
        UUID retired = UUID.randomUUID();
        when(categories.findById(retired)).thenReturn(Optional.of(node(retired, TAX_PROFILE_A, "old", false)));

        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.CATEGORY, "",
                Map.of("categoryId", retired.toString())), page, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.change-request-category-unknown"));
    }

    // ---- images: media's verdict ----------------------------------------------------------------------------------

    @Test
    void aMainImageThatMediaPassesIsPutFirstWithMediasSizeNotTheSellers() {
        media.put(MAIN, new ImageCheckResult(MAIN, true, "PASS", List.of(), 1600, 1200, 240_000, "image/jpeg", true));

        Product saved = applier.apply(request(ChangeRequestType.IMAGE_ADD, "MAIN", imagePayload(MAIN, 4000, 4000)), page, NOW);

        assertThat(saved.images().getFirst().url()).isEqualTo(MAIN);
        assertThat(saved.images().getFirst().width()).isEqualTo(1600);
        assertThat(saved.images().getFirst().height()).isEqualTo(1200);
        assertThat(saved.images().getFirst().bytes()).isEqualTo(240_000);
        assertThat(asked).singleElement().satisfies(i -> {
            assertThat(i.slot()).isEqualTo("MAIN");
            assertThat(i.productTypeCode()).isEqualTo("custom");
        });
        assertThat(saved.images()).filteredOn(ProductImage::isLabel).hasSize(1);
    }

    @Test
    void anAdditionalImageIsJudgedForTheOtherSlotAndAppended() {
        String side = "https://cdn.example/side2.jpg";
        media.put(side, new ImageCheckResult(side, true, "WARN", List.of(), 800, 800, 90_000, "image/jpeg", true));

        Product saved = applier.apply(request(ChangeRequestType.IMAGE_ADD, "OTHER", imagePayload(side, 800, 800)), page, NOW);

        assertThat(asked).singleElement().extracting(ImageVerifier.Item::slot).isEqualTo("OTHER");
        assertThat(saved.images().stream().filter(i -> !i.isLabel()).map(ProductImage::url))
                .containsExactly("https://cdn.example/old-main.jpg", "https://cdn.example/old-side.jpg", side);
    }

    @Test
    void aRejectedImageIsRefusedWithMediasPlainMessageAndNothingIsStored() {
        String message = "This photo is too small. The main photo must be at least 1000 pixels on its longest side so "
                + "shoppers can zoom. Yours is 800 by 800.";
        media.put(MAIN, new ImageCheckResult(MAIN, true, "REJECT",
                List.of(new ImageFinding("MAIN_TOO_SMALL", ImageFinding.REJECT, message)), 800, 800, 90_000, "image/jpeg",
                true));

        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.IMAGE_ADD, "MAIN", imagePayload(MAIN, 4000, 4000)),
                page, NOW)).isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.code()).isEqualTo("catalog.change-request-image-rejected");
                    assertThat(e.getMessage()).isEqualTo(message);
                });
        verify(products, never()).update(any());
        verify(events, never()).publish(any(), any(Product.class));
    }

    @Test
    void aPictureMediaDoesNotKnowIsRefused() {
        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.IMAGE_ADD, "MAIN", imagePayload(MAIN, 2000, 2000)),
                page, NOW)).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.code()).isEqualTo("catalog.change-request-image-rejected"));
    }

    @Test
    void aHeldImageCannotBeAppliedUntilTheImageReviewQueueDecidedIt() {
        media.put(MAIN, new ImageCheckResult(MAIN, true, "HOLD",
                List.of(new ImageFinding("HELD_TEXT", ImageFinding.HOLD, "We are checking this photo for text, logos or "
                        + "watermarks.")), 1600, 1600, 200_000, "image/jpeg", true));

        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.IMAGE_ADD, "MAIN", imagePayload(MAIN, 1600, 1600)),
                page, NOW)).isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.code()).isEqualTo("catalog.change-request-image-held");
                    assertThat(e.getMessage()).contains("held image review queue").contains("text, logos or watermarks");
                });
        verify(products, never()).update(any());
    }

    @Test
    void whenMediaIsDownWhileEnforcingNothingIsAppliedUnchecked() {
        mediaDown = true;

        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.IMAGE_ADD, "MAIN", imagePayload(MAIN, 1600, 1600)),
                page, NOW)).isInstanceOf(ImageServiceUnavailable.class);
        verify(products, never()).update(any());
    }

    @Test
    void inShadowModeAVerdictIsOnlyCountedAndTheImageIsApplied() {
        mode = Mode.SHADOW;
        applier = newApplier();
        media.put(MAIN, ImageCheckResult.unknown(MAIN));

        Product saved = applier.apply(request(ChangeRequestType.IMAGE_ADD, "MAIN", imagePayload(MAIN, 2000, 1500)), page, NOW);

        assertThat(saved.images().getFirst().url()).isEqualTo(MAIN);
        assertThat(saved.images().getFirst().width()).as("shadow keeps the number it was given").isEqualTo(2000);
    }

    @Test
    void withTheImageRulesOffMediaIsNotAsked() {
        mode = Mode.OFF;
        applier = newApplier();

        Product saved = applier.apply(request(ChangeRequestType.IMAGE_ADD, "OTHER", imagePayload(MAIN, 900, 900)), page, NOW);

        assertThat(asked).isEmpty();
        assertThat(saved.images()).extracting(ProductImage::url).contains(MAIN);
    }

    @Test
    void replacingThePictureAtPositionZeroIsJudgedAsTheMainImage() {
        media.put(MAIN, new ImageCheckResult(MAIN, true, "PASS", List.of(), 1500, 1500, 300_000, "image/png", true));

        Product saved = applier.apply(request(ChangeRequestType.IMAGE_REPLACE, "MAIN", withPosition(imagePayload(MAIN, 10, 10),
                0)), page, NOW);

        assertThat(asked).singleElement().extracting(ImageVerifier.Item::slot).isEqualTo("MAIN");
        assertThat(saved.images().stream().filter(i -> !i.isLabel()).map(ProductImage::url))
                .containsExactly(MAIN, "https://cdn.example/old-side.jpg");
        assertThat(saved.images().getFirst().contentType()).isEqualTo("image/png");
    }

    @Test
    void replacingALaterPictureIsJudgedForTheOtherSlot() {
        String better = "https://cdn.example/better-side.jpg";
        media.put(better, new ImageCheckResult(better, true, "PASS", List.of(), 1200, 1200, 150_000, "image/jpeg", true));

        Product saved = applier.apply(request(ChangeRequestType.IMAGE_REPLACE, "OTHER", withPosition(imagePayload(better, 5, 5),
                1)), page, NOW);

        assertThat(asked).singleElement().extracting(ImageVerifier.Item::slot).isEqualTo("OTHER");
        assertThat(saved.images().stream().filter(i -> !i.isLabel()).map(ProductImage::url))
                .containsExactly("https://cdn.example/old-main.jpg", better);
    }

    @Test
    void aPositionThatIsNotOnThePageIsRefusedBeforeMediaIsAsked() {
        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.IMAGE_REPLACE, "OTHER",
                withPosition(imagePayload(MAIN, 1600, 1600), 7)), page, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getMessage()).contains("position 7"));
        assertThat(asked).isEmpty();
    }

    @Test
    void aPageThatAlreadyHasTheMostPicturesTakesNoMore() {
        List<ProductImage> full = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            full.add(image("https://cdn.example/p" + i + ".jpg"));
        }
        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.IMAGE_ADD, "OTHER", imagePayload(MAIN, 1600, 1600)),
                page(full), NOW)).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.getMessage()).contains("already has 10 pictures"));
    }

    @Test
    void aPictureThatIsNotJpegPngOrWebpIsRefusedWithoutAskingMedia() {
        Map<String, Object> gif = imagePayload(MAIN, 1600, 1600);
        gif.put("contentType", "image/gif");

        assertThatThrownBy(() -> applier.apply(request(ChangeRequestType.IMAGE_ADD, "MAIN", gif), page, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(asked).isEmpty();
    }

    // ---- what the applier does not do -----------------------------------------------------------------------------

    @Test
    void requestsResolvedInOtherWaysAreNotAppliedAsContent() {
        for (ChangeRequestType type : List.of(ChangeRequestType.WRONG_MATCH, ChangeRequestType.DUPLICATE_PAGE,
                ChangeRequestType.NEW_VARIANT)) {
            assertThatThrownBy(() -> applier.apply(request(type, "", Map.of()), page, NOW))
                    .isInstanceOfSatisfying(BusinessRuleException.class,
                            e -> assertThat(e.code()).isEqualTo("catalog.change-request-not-a-content-change"));
        }
        verify(products, never()).update(any());
    }

    @Test
    void aContentChangeLeavesThePageInTheStatusItHadWhateverItWas() {
        for (ProductStatus status : ProductStatus.values()) {
            Product p = new Product(page.id(), page.atin(), page.owner(), CATEGORY, null, "custom", "Ankara tote",
                    page.attributes(), page.images(), "", page.variants(), status, null, NOW, NOW, 3);
            Product saved = applier.apply(request(ChangeRequestType.TITLE, "", Map.of("value", "A better title")), p, NOW);
            assertThat(saved.status()).isEqualTo(status);
        }
        ArgumentCaptor<Product> stored = ArgumentCaptor.forClass(Product.class);
        verify(products, times(ProductStatus.values().length)).update(stored.capture());
        assertThat(stored.getAllValues()).extracting(Product::version).containsOnly(3L);
        verify(events, times(ProductStatus.values().length)).publish(eq(CatalogEvents.PRODUCT_UPDATED),
                any(Product.class));
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private static ProductImage image(String url) {
        return new ProductImage(url, 1200, 1200, 200_000, "image/jpeg");
    }

    private static CategoryNode node(UUID id, UUID taxProfile, String code, boolean active) {
        return new CategoryNode(id, null, code, code, active ? CategoryLevel.PRODUCT_TYPE : CategoryLevel.PRODUCT_TYPE,
                code, "Bags › " + code, 0, active, 0, active ? null : NOW, active ? null : "retired", Set.of(),
                taxProfile == null ? null : taxProfile.toString(), null);
    }

    private static Map<String, Object> imagePayload(String url, int width, int height) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", url);
        payload.put("width", width);
        payload.put("height", height);
        payload.put("bytes", 500_000);
        payload.put("contentType", "image/jpeg");
        return payload;
    }

    private static Map<String, Object> withPosition(Map<String, Object> payload, int position) {
        payload.put("position", position);
        return payload;
    }

    private Product page(List<ProductImage> images) {
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT00000002"), "S-1", "012345678905", false, Map.of(), 899,
                "USD", 5);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(BrandKeys.NAME, "Adire Lagos");
        attributes.put(BrandKeys.ID, UUID.randomUUID().toString());
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), new ListingOwner(ListingOwnerKind.SELLER, "org_a"),
                CATEGORY, null, "custom", "Ankara tote", attributes, images, "", List.of(v), ProductStatus.LIVE, null, NOW,
                NOW.minusSeconds(60), 4);
    }

    private ProductChangeRequest request(ChangeRequestType type, String field, Map<String, Object> payload) {
        return ProductChangeRequest.filed(page.id(), "AT00000001", null, null, type, field, payload, null, "org_b", "user_b",
                NOW.minusSeconds(3600), NOW.plusSeconds(86_400));
    }
}
