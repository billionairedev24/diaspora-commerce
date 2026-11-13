package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.application.CatalogMatchService.MatchCommand;
import com.afrotransact.catalog.application.CatalogMatchService.MatchResult;
import com.afrotransact.catalog.application.CatalogMatchService.Result;
import com.afrotransact.catalog.application.OfferEligibilityService.Eligibility;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.GtinReader;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.MatchMetrics;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogMatchServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final CatalogPolicy POLICY = new CatalogPolicy(List.of("02", "04", "20", "21", "22", "23", "24", "25",
            "26", "27", "28", "29", "981", "982", "983", "984", "99"), "model", 2, 3, 1000, 1, List.of(), true, null, null,
            0);

    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ChangeRequestRepository changeRequests = mock(ChangeRequestRepository.class);
    private final OfferEligibilityService eligibility = mock(OfferEligibilityService.class);
    private final CatalogSettingsService settings = mock(CatalogSettingsService.class);
    private final MatchMetrics metrics = mock(MatchMetrics.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private CatalogMatchServiceImpl service;

    @BeforeEach
    void setUp() {
        when(settings.current()).thenReturn(POLICY);
        when(actors.current()).thenReturn(seller("org_b"));
        when(eligibility.evaluate(any(), any(), any())).thenAnswer(i -> {
            Product p = i.getArgument(0);
            Variant v = i.getArgument(1);
            return new Eligibility(v.atin().value(), p.atin().value(), true, List.of(), List.of(ItemCondition.NEW),
                    p.owner().orgId());
        });
        service = new CatalogMatchServiceImpl(products, categories, changeRequests, eligibility, settings,
                metrics, actors);
    }

    @Test
    void aUpcAndTheEanOfOneItemReturnTheSameAtin() {
        Product page = page("Ankara tote", ProductStatus.LIVE, "012345678905");
        when(products.findByVariantGtin(anyString())).thenReturn(Optional.of(page));

        MatchResult byUpc = service.match(new MatchCommand("012345678905", null));
        MatchResult byEan = service.match(new MatchCommand("0012345678905", null));

        assertThat(byUpc.result()).isEqualTo(Result.MATCH);
        assertThat(byUpc.atin()).isEqualTo(byEan.atin()).isEqualTo(page.variants().getFirst().atin().value());
        assertThat(byUpc.parentAtin()).isEqualTo(page.atin().value());
        assertThat(byUpc.gtin14()).isEqualTo("00012345678905").isEqualTo(byEan.gtin14());
        assertThat(byUpc.title()).isEqualTo("Ankara tote");
        assertThat(byUpc.canOffer()).isTrue();
        assertThat(byUpc.canCreate()).as("a matched item is sold on its page, not created again").isFalse();
        verify(metrics, times(2)).matched("MATCH");
    }

    @Test
    void anIsbn10IsConvertedToTheIsbn13BeforeTheLookup() {
        when(products.findByVariantGtin("9780306406157")).thenReturn(Optional.of(page("A book", ProductStatus.LIVE,
                "9780306406157")));

        MatchResult result = service.match(new MatchCommand("0-306-40615-2", null));

        assertThat(result.result()).isEqualTo(Result.MATCH);
        assertThat(result.digits()).isEqualTo("9780306406157");
    }

    @Test
    void aReservedPrefixAnswersRestrictedInPlainWords() {
        String code = "2012345678903";
        MatchResult result = service.match(new MatchCommand(code.substring(0, 12) + Gtin.checkDigit(code.substring(0, 12)), null));

        assertThat(result.result()).isEqualTo(Result.RESTRICTED);
        assertThat(result.message()).isEqualTo("This number is for store use or coupons, not a product barcode.");
        assertThat(result.canOffer()).isFalse();
        assertThat(result.canCreate()).isFalse();
        verify(metrics).matched("RESTRICTED");
    }

    @Test
    void aBadCheckDigitAnswersInvalidWithASuggestion() {
        MatchResult result = service.match(new MatchCommand("4006381333932", null));

        assertThat(result.result()).isEqualTo(Result.INVALID);
        assertThat(result.suggestion()).isEqualTo("4006381333931");
        assertThat(result.message()).isEqualTo("That barcode does not look right. Did you mean 4006381333931?");
        assertThat(result.canCreate()).isFalse();
    }

    @Test
    void noMatchLetsTheSellerCreateAPageUnlessAWrongMatchOnThatBarcodeIsOpen() {
        when(products.findByVariantGtin(anyString())).thenReturn(Optional.empty());

        MatchResult free = service.match(new MatchCommand("4006381333931", null));
        assertThat(free.result()).isEqualTo(Result.NO_MATCH);
        assertThat(free.canCreate()).isTrue();

        when(changeRequests.existsOpenWrongMatch(GtinReader.key14("4006381333931"))).thenReturn(true);
        MatchResult held = service.match(new MatchCommand("4006381333931", null));
        assertThat(held.result()).isEqualTo(Result.NO_MATCH);
        assertThat(held.canCreate()).isFalse();
        assertThat(held.createBlockers()).extracting(Blocker::code).containsExactly("catalog.wrong-match-open");
        assertThat(held.createBlockers().getFirst().message())
                .isEqualTo("We are checking this barcode. We will tell you within 2 business days.");
    }

    @Test
    void aMatchOnAPageTheSellerMayNotSellReportsBlockersAndCannotOffer() {
        Product page = page("Designer bag", ProductStatus.LIVE, "4006381333931");
        when(products.findByVariantGtin(anyString())).thenReturn(Optional.of(page));
        doReturn(new Eligibility(page.variants().getFirst().atin().value(), page.atin().value(), false,
                List.of(new Blocker("offers.selling-approval-required", "You need approval to sell this brand. Apply "
                        + "under Listings.")), List.of(ItemCondition.NEW), "org_a"))
                .when(eligibility).evaluate(any(), any(), anyString());

        MatchResult result = service.match(new MatchCommand("4006381333931", null));

        assertThat(result.result()).isEqualTo(Result.MATCH);
        assertThat(result.canOffer()).isFalse();
        assertThat(result.blockers()).extracting(Blocker::code).containsExactly("offers.selling-approval-required");
    }

    @Test
    void aSellerAsksAsItselfAndIgnoresAnotherOrganisationInTheRequest() {
        Product page = page("Ankara tote", ProductStatus.LIVE, "4006381333931");
        when(products.findByVariantGtin(anyString())).thenReturn(Optional.of(page));

        service.match(new MatchCommand("4006381333931", "org_someone_else"));

        verify(eligibility).evaluate(any(), any(), eq("org_b"));
    }

    @Test
    void aServiceCallerNamesTheOrganisation() {
        when(actors.current()).thenReturn(new Actor("offers-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of(CatalogPermissions.READ)));
        Product page = page("Ankara tote", ProductStatus.LIVE, "4006381333931");
        when(products.findByVariantGtin(anyString())).thenReturn(Optional.of(page));

        service.match(new MatchCommand("4006381333931", "org_c"));

        verify(eligibility).evaluate(any(), any(), eq("org_c"));
    }

    @Test
    void aCallerWithoutCatalogPermissionIsRefused() {
        when(actors.current()).thenReturn(new Actor("u", PrincipalType.SELLER_MEMBER, Optional.of("org_b"), Set.of(), Set.of()));

        assertThatThrownBy(() -> service.match(new MatchCommand("4006381333931", null)))
                .isInstanceOf(ForbiddenActionException.class);
    }

    private static Actor seller(String org) {
        return new Actor("user_" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of(), Set.of(CatalogPermissions.WRITE));
    }

    private static int counter = 2000;

    private static Product page(String title, ProductStatus status, String gtin) {
        Variant v = new Variant(UUID.randomUUID(), new Atin(String.format("AT%08d", ++counter)), "SKU-" + counter, gtin,
                gtin == null, Map.of(), 899, "USD", 5);
        return product(title, status, List.of(v));
    }

    private static Product product(String title, ProductStatus status, List<Variant> variants) {
        return new Product(UUID.randomUUID(), new Atin(String.format("AT%08d", ++counter)),
                new ListingOwner(ListingOwnerKind.SELLER, "org_a"), null, null, "custom", title, Map.of(), List.of(), "",
                variants, status, null, NOW, NOW, 0);
    }
}
