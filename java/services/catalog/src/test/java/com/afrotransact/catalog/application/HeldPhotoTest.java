package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.application.ImageSettings.Mode;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.images.ImageCheckResult;
import com.afrotransact.catalog.domain.images.ImageFinding;
import com.afrotransact.catalog.domain.tax.ImageHeld;
import com.afrotransact.catalog.domain.tax.LiveRequiresTax;
import com.afrotransact.catalog.domain.tax.TaxDecision;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ImageVerifier;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.afrotransact.catalog.port.TaxGateMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A photo held for review has no public URL: the listing refers to it by media asset id, can be saved and submitted,
 * and never goes live until media clears the photo (S390, S391; docs/engineering/tax-gate-paths.md).
 */
class HeldPhotoTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final ListingOwner OWNER = new ListingOwner(ListingOwnerKind.SELLER, "org_1");
    private static final TaxDecision DECISION = new TaxDecision("EXEMPT", "AT.GROCERY.X", "S001", "0%", "v1", "r");

    /** Plays media for asset ids and URLs. */
    private static final class FakeMedia implements ImageVerifier {

        final Map<String, ImageCheckResult> byAsset = new HashMap<>();
        final List<Item> asked = new ArrayList<>();

        FakeMedia held(String id) {
            byAsset.put(id, new ImageCheckResult("", true, "HOLD",
                    List.of(new ImageFinding("HELD_REVIEW", "HOLD", "A person is checking this photo.")), 1600, 1600, 100_000,
                    "image/jpeg", true, id, "HELD"));
            return this;
        }

        FakeMedia ready(String id, String url) {
            byAsset.put(id, new ImageCheckResult(url, true, "PASS", List.of(), 1600, 1600, 100_000, "image/jpeg", true, id,
                    "READY"));
            return this;
        }

        FakeMedia unknown(String id) {
            byAsset.put(id, new ImageCheckResult("", false, "REJECT", List.of(new ImageFinding("NOT_VERIFIED", "REJECT",
                    "We could not check this photo. Upload it again from your device or your image library.")), 0, 0, 0, "",
                    false, id, "UNKNOWN"));
            return this;
        }

        @Override
        public List<ImageCheckResult> verify(List<Item> items) {
            asked.addAll(items);
            return items.stream().map(i -> i.assetId() != null ? byAsset.get(i.assetId()) : ImageCheckResult.unknown(i.url()))
                    .toList();
        }

        @Override
        public List<ImageCheckResult> rescore(List<Item> items) {
            return verify(items);
        }
    }

    private static ImageGate gate(FakeMedia media, Mode mode) {
        return new ImageGateImpl(media, new ImageSettings(mode, 60, false, 50, Duration.ofSeconds(30), Duration.ofDays(30)));
    }

    private static ProductImage ref(String id) {
        return ProductImage.held(id, null, null);
    }

    @Test
    void aHeldMainPhotoIsKeptAsAReferenceAndIsNotAnError() {
        FakeMedia media = new FakeMedia().held("ast_1");

        ImageGate.Outcome outcome = gate(media, Mode.ENFORCE).check(List.of(ref("ast_1")), List.of(), "APPAREL_DRESS", Set.of(),
                "org_1");

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.held()).isTrue();
        assertThat(outcome.images()).singleElement().satisfies(i -> {
            assertThat(i.isHeld()).isTrue();
            assertThat(i.assetId()).isEqualTo("ast_1");
            assertThat(i.url()).isEmpty();
        });
        // Media is asked by id, for the main slot, for this seller's organisation: never by a URL the client made up.
        assertThat(media.asked).containsExactly(new ImageVerifier.Item("", "MAIN", "APPAREL_DRESS", "ast_1", "org_1"));
    }

    @Test
    void aClearedPhotoGivenByIdBecomesAnOrdinaryPictureWithMediasUrlAndSize() {
        FakeMedia media = new FakeMedia().ready("ast_1", "https://cdn/ast_1.jpg");

        ImageGate.Outcome outcome = gate(media, Mode.ENFORCE).check(List.of(ref("ast_1")), List.of(), "T", Set.of(), "org_1");

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.held()).isFalse();
        assertThat(outcome.images()).singleElement().satisfies(i -> {
            assertThat(i.isHeld()).isFalse();
            assertThat(i.assetId()).isNull();
            assertThat(i.url()).isEqualTo("https://cdn/ast_1.jpg");
            assertThat(i.width()).isEqualTo(1600);
        });
    }

    @Test
    void aMissingOrForeignPhotoIsRefusedInEveryMode() {
        FakeMedia media = new FakeMedia().unknown("ast_x");

        for (Mode mode : Mode.values()) {
            ImageGate.Outcome outcome = gate(media, mode).check(List.of(ref("ast_x")), List.of(), "T", Set.of(), "org_1");
            assertThat(outcome.errors()).as(mode.name()).containsKey("images[0]");
            assertThat(outcome.held()).isFalse();
        }
    }

    @Test
    void heldPhotosAreResolvedEvenWhenTheRulesAreOffButOtherPicturesAreNotAsked() {
        FakeMedia media = new FakeMedia().held("ast_1");
        ProductImage other = new ProductImage("https://cdn/other.jpg", 100, 100, 10, "image/jpeg");

        ImageGate.Outcome outcome = gate(media, Mode.OFF).check(List.of(ref("ast_1"), other), List.of(), "T", Set.of(), "org_1");

        assertThat(outcome.held()).isTrue();
        assertThat(media.asked).extracting(ImageVerifier.Item::assetId).containsExactly("ast_1");
    }

    @Test
    void aHeldVariantPhotoIsKeptAsAReferenceAndHoldsTheListing() {
        FakeMedia media = new FakeMedia().held("ast_1");

        ImageGate.Outcome outcome = gate(media, Mode.ENFORCE).check(List.of(), List.of(List.of(ref("ast_1"))), "T", Set.of(),
                "org_1");

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.held()).isTrue();
        assertThat(outcome.variantImages()).hasSize(1);
        assertThat(outcome.variantImages().getFirst()).singleElement().satisfies(i -> assertThat(i.assetId()).isEqualTo("ast_1"));
        assertThat(media.asked).contains(new ImageVerifier.Item("", "OTHER", "T", "ast_1", "org_1"));
    }

    @Test
    void aClearedVariantPhotoIsPublishedWithMediasUrl() {
        FakeMedia media = new FakeMedia().ready("ast_1", "https://cdn/ast_1.jpg");

        ImageGate.Outcome outcome = gate(media, Mode.ENFORCE).check(List.of(), List.of(List.of(ref("ast_1"))), "T", Set.of(),
                "org_1");

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.held()).isFalse();
        assertThat(outcome.variantImages().getFirst()).singleElement().satisfies(i -> {
            assertThat(i.isHeld()).isFalse();
            assertThat(i.url()).isEqualTo("https://cdn/ast_1.jpg");
        });
    }

    @Test
    void aMissingOrForeignVariantPhotoIsRefusedOnThatVariantInEveryMode() {
        FakeMedia media = new FakeMedia().unknown("ast_x");

        for (Mode mode : Mode.values()) {
            ImageGate.Outcome outcome = gate(media, mode).check(List.of(), List.of(List.of(), List.of(ref("ast_x"))), "T",
                    Set.of(), "org_1");
            assertThat(outcome.errors()).as(mode.name()).containsOnlyKeys("variants[1].images");
        }
    }

    @Test
    void aVariantPhotoIsResolvedEvenWhenTheRulesAreOff() {
        FakeMedia media = new FakeMedia().held("ast_1");

        ImageGate.Outcome outcome = gate(media, Mode.OFF).check(List.of(), List.of(List.of(ref("ast_1"))), "T", Set.of(), "org_1");

        assertThat(outcome.held()).isTrue();
        assertThat(media.asked).extracting(ImageVerifier.Item::assetId).containsExactly("ast_1");
    }

    @Test
    void aHeldVariantPhotoOfAStoredListingIsPublishedOnceMediaClearsIt() {
        FakeMedia media = new FakeMedia().ready("ast_1", "https://cdn/ast_1.jpg").held("ast_2");

        ImageGate.Resolved resolved = gate(media, Mode.ENFORCE).resolveHeld(List.of(),
                List.of(List.of(ref("ast_1")), List.of(ref("ast_2"))), "org_1");

        assertThat(resolved.stillHeld()).isTrue();
        assertThat(resolved.variantImages().get(0).getFirst().url()).isEqualTo("https://cdn/ast_1.jpg");
        assertThat(resolved.variantImages().get(1).getFirst().isHeld()).isTrue();
    }

    @Test
    void aHeldPhotoOfAStoredListingIsPublishedOnceMediaClearsIt() {
        FakeMedia media = new FakeMedia().ready("ast_1", "https://cdn/ast_1.jpg").held("ast_2");

        ImageGate.Resolved resolved = gate(media, Mode.ENFORCE).resolveHeld(List.of(ref("ast_1"), ref("ast_2")), "org_1");

        assertThat(resolved.stillHeld()).isTrue();
        assertThat(resolved.images().get(0).url()).isEqualTo("https://cdn/ast_1.jpg");
        assertThat(resolved.images().get(1).isHeld()).isTrue();
    }

    // The live gate: a held photo keeps a listing off sale next to the tax gate.

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductTaxHoldRepository holds = mock(ProductTaxHoldRepository.class);
    private final ProductEvents events = mock(ProductEvents.class);
    private final ImageGate images = mock(ImageGate.class);
    private ProductLiveGateImpl liveGate;

    @BeforeEach
    void setUp() {
        liveGate = new ProductLiveGateImpl(products, holds, mock(CategoryRepository.class), events,
                mock(SellingPublishGate.class), mock(TaxGateMetrics.class),
                new TaxAuthorityCheck(mock(TaxDecisionAuthority.class), mock(TaxGateMetrics.class)), images,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(products.update(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static Product product(ProductStatus status, ProductImage... pictures) {
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT00000011"), "SKU-1", null, true, Map.of(), 100, "USD", 1, null,
                List.of(), false, new VariantTax(Map.of(), TaxStatus.READY, List.of(), DECISION));
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), OWNER, null, null, "packaged-snacks", "Plantain chips",
                Map.of(), List.of(pictures), "", List.of(v), status, null, NOW, NOW, 0);
    }

    @Test
    void aProductWithAHeldPhotoIsNeverLiveEvenWithResolvedTax() {
        assertThat(LiveRequiresTax.holds(product(ProductStatus.LIVE, ref("ast_1")))).isFalse();
        assertThat(LiveRequiresTax.holds(product(ProductStatus.IN_REVIEW, ref("ast_1")))).isTrue();
        assertThat(LiveRequiresTax.reasonCode(product(ProductStatus.LIVE, ref("ast_1")))).isEqualTo("IMAGE_HELD");
        assertThatThrownBy(() -> LiveRequiresTax.require(product(ProductStatus.LIVE, ref("ast_1"))))
                .isInstanceOf(ImageHeld.class).hasMessageContaining("checking your photo");
    }

    @Test
    void approveRefusesWhileThePhotoIsHeldAndWorksOnceItIsCleared() {
        Product p = product(ProductStatus.IN_REVIEW, ref("ast_1"));
        when(images.resolveHeld(any(), any(), eq("org_1"))).thenReturn(new ImageGate.Resolved(p.images(), true, false));
        assertThatThrownBy(() -> liveGate.approve(p, NOW)).isInstanceOf(ImageHeld.class)
                .hasMessageContaining("checking your photo");

        ProductImage published = new ProductImage("https://cdn/ast_1.jpg", 1600, 1600, 100_000, "image/jpeg");
        when(images.resolveHeld(any(), any(), eq("org_1"))).thenReturn(new ImageGate.Resolved(List.of(published), false, false));
        Product live = liveGate.approve(p, NOW);
        assertThat(live.status()).isEqualTo(ProductStatus.LIVE);
        assertThat(live.images()).containsExactly(published);
    }

    private static Product productWithVariantPhoto(ProductStatus status, ProductImage variantPicture) {
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT00000011"), "SKU-1", null, true, Map.of(), 100, "USD", 1, null,
                List.of(variantPicture), false, new VariantTax(Map.of(), TaxStatus.READY, List.of(), DECISION));
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), OWNER, null, null, "packaged-snacks", "Plantain chips",
                Map.of(), List.of(new ProductImage("https://cdn/main.jpg", 1, 1, 1, "image/jpeg")), "", List.of(v), status,
                null, NOW, NOW, 0);
    }

    @Test
    void approveRefusesWhileAVariantPhotoIsHeldAndPublishesItOnceCleared() {
        Product p = productWithVariantPhoto(ProductStatus.IN_REVIEW, ref("ast_1"));
        when(images.resolveHeld(any(), any(), eq("org_1")))
                .thenReturn(new ImageGate.Resolved(p.images(), List.of(List.of(ref("ast_1"))), true, false));
        assertThatThrownBy(() -> liveGate.approve(p, NOW)).isInstanceOf(ImageHeld.class)
                .hasMessageContaining("checking your photo");

        ProductImage published = new ProductImage("https://cdn/ast_1.jpg", 1600, 1600, 100_000, "image/jpeg");
        when(images.resolveHeld(any(), any(), eq("org_1")))
                .thenReturn(new ImageGate.Resolved(p.images(), List.of(List.of(published)), false, false));
        Product live = liveGate.approve(p, NOW);
        assertThat(live.status()).isEqualTo(ProductStatus.LIVE);
        assertThat(live.variants().getFirst().images()).containsExactly(published);
    }

    @Test
    void reconcilePublishesAClearedVariantPhotoOnTheStoredListing() {
        Product p = productWithVariantPhoto(ProductStatus.IN_REVIEW, ref("ast_1"));
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        ProductImage published = new ProductImage("https://cdn/ast_1.jpg", 1600, 1600, 100_000, "image/jpeg");
        when(images.resolveHeld(any(), any(), eq("org_1")))
                .thenReturn(new ImageGate.Resolved(p.images(), List.of(List.of(published)), false, false));

        liveGate.reconcile(p.id());

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products, atLeastOnce()).update(saved.capture());
        assertThat(saved.getAllValues().getFirst().variants().getFirst().images()).containsExactly(published);
    }

    @Test
    void approveDoesNotAskMediaWhenThereIsNoHeldPhoto() {
        Product p = product(ProductStatus.IN_REVIEW, new ProductImage("https://cdn/a.jpg", 1, 1, 1, "image/jpeg"));
        assertThat(liveGate.approve(p, NOW).status()).isEqualTo(ProductStatus.LIVE);
        verify(images, never()).resolveHeld(any(), any(), any());
    }

    @Test
    void aLiveListingEditedWithAHeldPhotoIsClosedWithThePhotoNote() {
        Product edited = liveGate.closeIfNotReady(product(ProductStatus.LIVE, ref("ast_1")), NOW);

        assertThat(edited.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(edited.moderationNote()).isEqualTo(Product.IMAGE_HOLD_NOTE);
        verify(holds).hold(edited.id(), "IMAGE_HELD", NOW);
        verify(events).publishWithReason(eq(CatalogEvents.PRODUCT_PAUSED), any(), eq("IMAGE_HELD"));
    }

    @Test
    void theEventNeverAnnouncesAHeldListingAsLiveNorShowsAnEmptyImageUrl() {
        ProductEventPayload payload = ProductEventPayload.of(product(ProductStatus.LIVE, ref("ast_1")), false);

        assertThat(payload.status()).isEqualTo("IN_REVIEW");
        assertThat(payload.imageUrl()).isNull();
    }
}
