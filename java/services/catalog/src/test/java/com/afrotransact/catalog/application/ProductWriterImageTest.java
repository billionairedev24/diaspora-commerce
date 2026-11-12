package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.catalog.application.ImageSettings.Mode;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.DefaultProductDraftPolicy;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.images.ImageCheckResult;
import com.afrotransact.catalog.domain.images.ImageFinding;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;

/**
 * S390: the product writer (the form, the API, the bulk job and the image library attach all write through it) refuses a
 * main image media does not pass, ignores the width and height a client sent, and judges only new pictures on an edit.
 */
class ProductWriterImageTest {

    private static final ListingOwner SELLER = new ListingOwner(ListingOwnerKind.SELLER, SizingFixtures.OWNER);
    private static final String SMALL = "This photo is too small. The main photo must be at least 1000 pixels on its "
            + "longest side so shoppers can zoom. Yours is 800 by 800.";

    private final Map<String, ImageCheckResult> media = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
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
        when(products.updateWithVariants(any())).thenAnswer(i -> i.getArgument(0));
        BrandResolver brands = mock(BrandResolver.class);
        when(brands.resolve(any(), any(), any(), any(), any())).thenAnswer(i -> i.getArgument(3));
        ImageVerifier verifier = new ImageVerifier() {
            @Override
            public List<ImageCheckResult> verify(List<Item> items) {
                items.forEach(i -> asked.add(i.slot() + " " + i.url()));
                return items.stream().map(i -> media.getOrDefault(i.url(), ImageCheckResult.unknown(i.url()))).toList();
            }

            @Override
            public List<ImageCheckResult> rescore(List<Item> items) {
                return verify(items);
            }
        };
        ImageGate gate = new ImageGateImpl(verifier,
                new ImageSettings(Mode.ENFORCE, 60, false, 50, Duration.ofSeconds(30), Duration.ofDays(30)));
        writer = new ProductWriterImpl(products, categories, mock(CategoryRequestRepository.class),
                mock(ProductDraftRepository.class), ids, new DefaultProductDraftPolicy(), new DefaultSizeChartPolicy(),
                mock(SizeChartRepository.class), mock(ProductEvents.class), brands, new UnrestrictedScopeGuard(),
                new TaxGateEvaluator(request -> new TaxResolver.Resolution("RESOLVED", "EXEMPT", "AT.X", "S001", "0%",
                        null, "v", "r"), false),
                RulesFixtures.permissiveService(), mock(ProductLiveGate.class, AdditionalAnswers.returnsFirstArg()),
                Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC), gate);
    }

    private void mediaKnows(String url, int w, int h) {
        media.put(url, new ImageCheckResult(url, true, "PASS", List.of(), w, h, 250_000, "image/jpeg", true));
    }

    private void mediaRejects(String url, int w, int h, String code, String message) {
        media.put(url, new ImageCheckResult(url, true, "REJECT", List.of(new ImageFinding(code, "REJECT", message)), w, h,
                90_000, "image/jpeg", true));
    }

    /** The client claims 4000 x 4000 for every picture, whatever it really is. */
    private static ProductImage claimed(String url) {
        return new ProductImage(url, 4000, 4000, 1_000_000, "image/jpeg");
    }

    private CreateProductCommand create(ProductImage... images) {
        return new CreateProductCommand(node, null, "Ankara Wrap Dress, Puff Sleeve", Map.of("brand", "Adire Lagos"),
                List.of(images), "", List.of(new VariantDraft(Map.of(), "A-1", "", true, 4900, 5)), "USD");
    }

    @Test
    void theFormAndTheApiRefuseAMainImageUnder1000PixelsWhateverTheClientSays() {
        mediaRejects("https://cdn/main.jpg", 800, 800, "MAIN_TOO_SMALL", SMALL);

        assertThatThrownBy(() -> writer.create(SELLER, create(claimed("https://cdn/main.jpg"))))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsEntry("images[0]", SMALL));
        assertThat(asked).containsExactly("MAIN https://cdn/main.jpg");
    }

    @Test
    void aMainImageMediaPassesIsStoredWithMediasSizeNotTheClients() {
        mediaKnows("https://cdn/main.jpg", 1600, 1200);
        mediaKnows("https://cdn/side.jpg", 700, 700);

        Product created = writer.create(SELLER, create(claimed("https://cdn/main.jpg"), claimed("https://cdn/side.jpg")));

        assertThat(created.images()).extracting(ProductImage::width, ProductImage::height, ProductImage::bytes)
                .containsExactly(tuple(1600, 1200, 250_000L),
                        tuple(700, 700, 250_000L));
        assertThat(asked).containsExactly("MAIN https://cdn/main.jpg", "OTHER https://cdn/side.jpg");
    }

    @Test
    void aPictureMediaDoesNotKnowIsRefusedAsTheMainImage() {
        assertThatThrownBy(() -> writer.create(SELLER, create(claimed("https://elsewhere.example.com/a.jpg"))))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fieldErrors().get("images[0]"))
                        .startsWith("We could not check this photo"));
    }

    @Test
    void anEditKeepsAGrandfatheredMainImageButRefusesANewSmallOne() {
        mediaKnows("https://cdn/legacy-main.jpg", 1600, 1600);
        Product current = writer.create(SELLER, create(claimed("https://cdn/legacy-main.jpg")));
        asked.clear();
        media.clear(); // media no longer vouches for the old picture (rules changed, or it was never measured)

        VariantDraft same = new VariantDraft(Map.of(), "A-1", "", true, 5900, 5);
        Product edited = writer.update(current, new UpdateProductCommand(null, "Ankara Wrap Dress, Puff Sleeve",
                current.attributes(), current.images(), "", List.of(same), null, null, current.details(), null, null));
        assertThat(edited.variants().getFirst().priceMinor()).isEqualTo(5900);
        assertThat(asked).as("only new pictures are judged").isEmpty();

        mediaRejects("https://cdn/new-main.jpg", 800, 800, "MAIN_TOO_SMALL", SMALL);
        assertThatThrownBy(() -> writer.update(current, new UpdateProductCommand(null, "Ankara Wrap Dress, Puff Sleeve",
                current.attributes(), List.of(claimed("https://cdn/new-main.jpg"), claimed("https://cdn/legacy-main.jpg")),
                "", List.of(same), null, null, current.details(), null, null)))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsEntry("images[0]", SMALL));
        assertThat(asked).containsExactly("MAIN https://cdn/new-main.jpg");
    }
}
