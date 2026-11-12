package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.catalog.adapter.out.tax.LocalDatasetTaxResolver;
import com.afrotransact.catalog.adapter.out.taxonomy.ClasspathTaxonomySource;
import com.afrotransact.catalog.testsupport.ImageGates;
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
import com.afrotransact.catalog.domain.tax.SellerSkus;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SizeChartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.InOrder;

/** The publish gate in the product writer: tax facts, the listing's behaviour answers and the optional, generated SKU. */
class ProductWriterTaxGateTest {

    private static final ClasspathTaxonomySource SOURCE = new ClasspathTaxonomySource();
    private static final TaxonomyPlanner.Plan PLAN = TaxonomyPlanner.plan(SOURCE.bundled(), SOURCE.schemas());
    private static final ListingOwner SELLER = new ListingOwner(ListingOwnerKind.SELLER, "org_lagos_spice");
    private static final ProductImage BIG = new ProductImage("https://utfs.io/f/a.jpg", 1200, 1200, 200_000, "image/jpeg");
    private static final Map<String, String> DETAILS = Map.of("ingredients", "Plantain, palm oil, salt", "allergens", "None");

    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductEvents events = mock(ProductEvents.class);
    private final BrandResolver brands = mock(BrandResolver.class);
    private final ProductLiveGate liveGate = mock(ProductLiveGate.class, AdditionalAnswers.returnsFirstArg());
    private final UUID node = UUID.randomUUID();
    private ProductWriterImpl writer;

    @BeforeEach
    void setUp() {
        AtomicInteger atins = new AtomicInteger(1000);
        IdentifierIssuer ids = mock(IdentifierIssuer.class);
        when(ids.nextAtin()).thenAnswer(i -> new Atin(String.format("AT%08d", atins.incrementAndGet())));
        ProductType type = PLAN.types().stream().filter(t -> t.code().equals("packaged-snacks")).findFirst().orElseThrow();
        when(categories.findById(node)).thenReturn(Optional.of(new CategoryNode(node, null, "packaged-snacks",
                "Packaged snacks", CategoryLevel.PRODUCT_TYPE, "packaged-snacks", "Groceries & Food › Packaged snacks › Packaged snacks",
                0, true, 0, null, null).withTax("packaged-snacks", "ACTIVE")));
        when(categories.findProductType("packaged-snacks")).thenReturn(Optional.of(type));
        when(products.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(brands.resolve(any(), any(), any(), any(), any())).thenAnswer(i -> i.getArgument(3));
        writer = new ProductWriterImpl(products, categories, mock(CategoryRequestRepository.class),
                mock(ProductDraftRepository.class), ids, new DefaultProductDraftPolicy(), new DefaultSizeChartPolicy(),
                mock(SizeChartRepository.class), events, brands, new UnrestrictedScopeGuard(),
                new TaxGateEvaluator(new LocalDatasetTaxResolver(SOURCE), true), RulesFixtures.permissiveService(),
                liveGate, mock(NewPageGuard.class), Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC),
                ImageGates.passthrough());
    }

    private CreateProductCommand command(String sku, Map<String, Object> facts) {
        VariantDraft v = new VariantDraft(Map.of(), sku, "", true, 650, 20, null, List.of(), null, facts);
        return new CreateProductCommand(node, null, "Crispy plantain chips, 8 oz bag", DETAILS, List.of(BIG), "",
                List.of(v), "USD", null, null, ListingDetails.DEFAULT, null);
    }

    private static final Map<String, Object> FACTS = Map.of("is_candy", false, "net_weight_oz", 8, "servings_per_package", 4,
            "individual_serving", false);

    @Test
    void aProductWithoutItsTaxFactsIsRefusedWithAPlainMessagePerFact() {
        assertThatThrownBy(() -> writer.create(SELLER, command("CHIPS-8", Map.of("is_candy", false))))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> {
                    assertThat(e.fieldErrors()).containsKeys("variants[0].taxFacts.net_weight_oz",
                            "variants[0].taxFacts.servings_per_package", "variants[0].taxFacts.individual_serving");
                    assertThat(e.fieldErrors().get("variants[0].taxFacts.net_weight_oz"))
                            .startsWith("Net weight (oz) is needed.");
                });
        verify(products, never()).insert(any());
    }

    @Test
    void aCompleteProductIsCreatedWithItsTaxStateAndPublishesTheFactsEvent() {
        Product p = writer.create(SELLER, command("CHIPS-8", FACTS));
        assertThat(p.variants().getFirst().tax().status()).isEqualTo(TaxStatus.PENDING_REVIEW);
        assertThat(p.variants().getFirst().tax().facts()).containsKeys("net_weight_oz", "servings_per_package");
        assertThat(p.variants().getFirst().tax().decision().internalTaxCode()).startsWith("AT.PACKAGED_SNACKS.");
        assertThat(p.variants().getFirst().sellerSku()).isEqualTo("CHIPS-8");
        assertThat(p.variants().getFirst().sellerSkuGenerated()).isFalse();
        verify(events).publishTaxFactsChanged(any(), any(), any(), any(), any());
    }

    @Test
    void anEmptySkuIsGeneratedStableOrganisationPrefixedAndMarked() {
        Product p = writer.create(SELLER, command("", FACTS));
        String sku = p.variants().getFirst().sellerSku();
        assertThat(sku).isEqualTo(SellerSkus.generate("org_lagos_spice", p.variants().getFirst().atin().value()));
        assertThat(p.variants().getFirst().sellerSkuGenerated()).isTrue();
    }

    @Test
    void aGeneratedSkuThatTheSellerAlreadyUsedGetsASuffix() {
        String taken = SellerSkus.generate("org_lagos_spice", "AT00001001");
        when(products.sellerSkuTaken("org_lagos_spice", taken)).thenReturn(true);
        Product p = writer.create(SELLER, command("", FACTS));
        assertThat(p.variants().getFirst().sellerSku()).isEqualTo(taken + "-2");
    }

    @Test
    void aTypedSkuThatIsTakenIsStillAConflict() {
        when(products.sellerSkuTaken(anyString(), anyString())).thenReturn(true);
        assertThatThrownBy(() -> writer.create(SELLER, command("CHIPS-8", FACTS))).hasMessageContaining("already used");
    }

    @Test
    void twoRowsWithTheSameTypedSkuAreRefusedButTwoEmptyOnesAreFine() {
        VariantDraft a = new VariantDraft(Map.of("Net weight", "8 oz"), "", "", true, 650, 20, null, List.of(), null, FACTS);
        VariantDraft b = new VariantDraft(Map.of("Net weight", "1 lb"), "", "", true, 1100, 20, null, List.of(), null,
                Map.of("is_candy", false, "net_weight_oz", 16, "servings_per_package", 8, "individual_serving", false));
        CreateProductCommand cmd = new CreateProductCommand(node, null, "Crispy plantain chips, two sizes", DETAILS,
                List.of(BIG), "Net weight", List.of(a, b), "USD", null, null, ListingDetails.DEFAULT, null);
        Product p;
        try {
            p = writer.create(SELLER, cmd);
        } catch (CatalogRuleViolation e) {
            throw new AssertionError(e.fieldErrors().toString());
        }
        assertThat(p.variants()).extracting(v -> v.sellerSku()).doesNotHaveDuplicates();
        assertThat(p.variants()).allMatch(v -> v.sellerSkuGenerated());
    }

    @Test
    void anEditMatchesVariantsBySkuThenGtinThenAtinAndKeepsTheGeneratedSku() {
        Product created = writer.create(SELLER, command("", FACTS));
        when(products.updateWithVariants(any())).thenAnswer(i -> i.getArgument(0));
        String atin = created.variants().getFirst().atin().value();
        VariantDraft byAtin = new VariantDraft(Map.of(), "", "", true, 700, 15, null, List.of(), atin, FACTS);
        UpdateProductCommand edit = new UpdateProductCommand(null, "Crispy plantain chips, 8 oz bag", DETAILS, List.of(BIG), "",
                List.of(byAtin), null, null, ListingDetails.DEFAULT, null, null);

        Product edited = writer.update(created, edit);

        assertThat(edited.variants()).hasSize(1);
        assertThat(edited.variants().getFirst().id()).isEqualTo(created.variants().getFirst().id());
        assertThat(edited.variants().getFirst().sellerSku()).isEqualTo(created.variants().getFirst().sellerSku());
        assertThat(edited.variants().getFirst().sellerSkuGenerated()).isTrue();
        assertThat(edited.variants().getFirst().priceMinor()).isEqualTo(700);
    }

    @Test
    void everyEditGoesThroughTheLiveGateSoALiveListingWithAnUnresolvedVariantIsClosedBeforeItIsStored() {
        Product created = writer.create(SELLER, command("", FACTS));
        when(products.updateWithVariants(any())).thenAnswer(i -> i.getArgument(0));
        String atin = created.variants().getFirst().atin().value();
        VariantDraft byAtin = new VariantDraft(Map.of(), "", "", true, 700, 15, null, List.of(), atin, FACTS);
        UpdateProductCommand edit = new UpdateProductCommand(null, "Crispy plantain chips, 8 oz bag", DETAILS, List.of(BIG), "",
                List.of(byAtin), null, null, ListingDetails.DEFAULT, null, null);

        writer.update(created, edit);

        InOrder order = inOrder(liveGate, products);
        order.verify(liveGate).closeIfNotReady(any(), any());
        order.verify(products).updateWithVariants(any());
    }
}
