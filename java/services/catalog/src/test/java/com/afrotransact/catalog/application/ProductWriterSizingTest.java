package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.catalog.testsupport.RulesFixtures;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.DefaultProductDraftPolicy;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.sizing.DefaultSizeChartPolicy;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.domain.sizing.SizingFixtures;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SizeChartRepository;
import com.afrotransact.catalog.port.TaxResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The product writer applies the size rules of the product type to the form and to bulk uploads alike. */
class ProductWriterSizingTest {

    private static final String OWNER = SizingFixtures.OWNER;
    private static final ListingOwner SELLER = new ListingOwner(ListingOwnerKind.SELLER, OWNER);
    private static final ProductImage BIG = new ProductImage("https://utfs.io/f/a.jpg", 1200, 1200, 200_000,
            "image/jpeg");

    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final SizeChartRepository charts = mock(SizeChartRepository.class);
    private final BrandResolver brands = mock(BrandResolver.class);
    private final UUID node = UUID.randomUUID();
    private ProductWriterImpl writer;
    private ProductType type = SizingFixtures.DRESS_CHART_REQUIRED;

    @BeforeEach
    void setUp() {
        AtomicInteger atins = new AtomicInteger();
        IdentifierIssuer ids = mock(IdentifierIssuer.class);
        when(ids.nextAtin()).thenAnswer(i -> new Atin(String.format("AT%08d", atins.incrementAndGet())));
        when(categories.findById(node)).thenReturn(Optional.of(new CategoryNode(node, null, "dresses", "Dresses",
                CategoryLevel.PRODUCT_TYPE, "dress", "Fashion › Women › Dresses", 0, true, 0, null, null)));
        when(categories.findProductType("dress")).thenAnswer(i -> Optional.of(type));
        when(products.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(brands.resolve(any(), any(), any(), any(), any())).thenAnswer(i -> i.getArgument(3));
        writer = new ProductWriterImpl(products, categories, mock(CategoryRequestRepository.class),
                mock(ProductDraftRepository.class), ids, new DefaultProductDraftPolicy(), new DefaultSizeChartPolicy(),
                charts, mock(ProductEvents.class), brands, new UnrestrictedScopeGuard(), new TaxGateEvaluator(readyResolver(), false), RulesFixtures.permissiveService(), Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"),
                        ZoneOffset.UTC));
    }

    private CreateProductCommand command(UUID chartId, FitNote fit, VariantDraft... variants) {
        ListingDetails details = ListingDetails.DEFAULT.withSizing(chartId, fit);
        return new CreateProductCommand(node, null, "Ankara Wrap Dress, Puff Sleeve", Map.of("brand", "Adire Lagos"),
                List.of(BIG), "Size", List.of(variants), "USD", null, null, details, null);
    }

    private static VariantDraft variant(String sku, String size) {
        return new VariantDraft(Map.of("Size", size), sku, "", true, 4900, 5);
    }

    private SizeChart stored(String owner, String name) {
        SizeChart chart = SizingFixtures.dressChart(owner, name);
        when(charts.findById(chart.id())).thenReturn(Optional.of(chart));
        return chart;
    }

    private static TaxResolver readyResolver() {
        return request -> new TaxResolver.Resolution("RESOLVED", "EXEMPT", "AT.X", "S001", "0%", null, "v", "r");
    }

    @Test
    void aRequiredChartIsEnforcedAndReportedTogetherWithTheOtherProblems() {
        assertThatThrownBy(() -> writer.create(SELLER, command(null, null, variant("A-1", "<b>"))))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsKeys("sizeChartId", "variants[0].options.Size"));
    }

    @Test
    void aProductWithAChartThatCoversItsSizesIsCreatedWithTheLinkAndFitNote() {
        SizeChart chart = stored(OWNER, "Adire dresses");

        Product created = writer.create(SELLER, command(chart.id(), FitNote.RUNS_SMALL, variant("A-S", "s"),
                variant("A-M", " M ")));

        assertThat(created.details().sizeChartId()).isEqualTo(chart.id());
        assertThat(created.details().fitNote()).isEqualTo(FitNote.RUNS_SMALL);
        assertThat(created.variants()).extracting(v -> v.options().get("Size")).containsExactly("S", "M");
    }

    @Test
    void aSizeTakesTheSpellingOfTheChartsRow() {
        type = SizingFixtures.DRESS;
        SizeChart chart = stored(OWNER, "UK sizes");
        SizeChart custom = SizingFixtures.chart(OWNER, "UK sizes", SizingFixtures.row("Free size", "90", "110", "bust"));
        when(charts.findById(chart.id())).thenReturn(Optional.of(custom));

        Product created = writer.create(SELLER, command(chart.id(), null, variant("A-F", "free SIZE")));

        assertThat(created.variants().getFirst().options()).containsEntry("Size", "Free size");
    }

    @Test
    void aSizeTheChartLacksIsRefusedOnTheVariantField() {
        SizeChart chart = stored(OWNER, "Adire dresses");

        assertThatThrownBy(() -> writer.create(SELLER, command(chart.id(), null, variant("A-S", "S"),
                variant("A-XL", "XL")))).isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(
                        e.fieldErrors()).containsOnlyKeys("variants[1].options.Size"));
    }

    @Test
    void aChartOfAnotherSellerCannotBeLinked() {
        SizeChart foreign = stored("org_other", "Their dresses");

        assertThatThrownBy(() -> writer.create(SELLER, command(foreign.id(), null, variant("A-S", "S"))))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsEntry("sizeChartId", "Size chart not found"));
    }

    @Test
    void anOptionalChartMayBeOmittedAndCustomSizesAreStored() {
        type = SizingFixtures.DRESS;

        Product created = writer.create(SELLER, command(null, null, variant("A-1", "UK 12"), variant("A-2", "xl")));

        assertThat(created.details().sizeChartId()).isNull();
        assertThat(created.variants()).extracting(v -> v.options().get("Size")).containsExactly("UK 12", "XL");
    }
}
