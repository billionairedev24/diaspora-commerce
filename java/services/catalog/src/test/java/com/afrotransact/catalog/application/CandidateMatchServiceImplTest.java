package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.application.CandidateMatchService.Candidate;
import com.afrotransact.catalog.application.CandidateMatchService.CandidateCommand;
import com.afrotransact.catalog.application.CandidateMatchService.CandidateResult;
import com.afrotransact.catalog.application.CandidateMatchService.Choice;
import com.afrotransact.catalog.application.CandidateMatchService.DecisionCommand;
import com.afrotransact.catalog.application.CandidateMatchService.DecisionResult;
import com.afrotransact.catalog.application.CandidateMatchService.RowKind;
import com.afrotransact.catalog.application.CandidateMatchService.RowMatch;
import com.afrotransact.catalog.application.OfferEligibilityService.Eligibility;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.matching.CandidateRanker.Kind;
import com.afrotransact.catalog.domain.matching.MatchThresholds;
import com.afrotransact.catalog.port.CandidateRecall;
import com.afrotransact.catalog.port.CandidateRecall.RecallUnavailable;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.MatchDecisionRepository;
import com.afrotransact.catalog.port.MatchDecisionRepository.Decision;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CandidateMatchServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final UUID RICE = UUID.randomUUID();
    private static final UUID SHOES = UUID.randomUUID();
    private static final CatalogPolicy POLICY = new CatalogPolicy(List.of(), "model", 2, 3, 1000, 1, List.of(), true, null,
            null, 0);

    private final CandidateRecall recall = mock(CandidateRecall.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final OfferEligibilityService eligibility = mock(OfferEligibilityService.class);
    private final CatalogSettingsService settings = mock(CatalogSettingsService.class);
    private final MatchDecisionRepository decisions = mock(MatchDecisionRepository.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SearchRateLimiter limiter = new SearchRateLimiterImpl(clock);
    private final MatchThresholds thresholds = new MatchThresholds(45, 75, 6, 30, 3, 5, 2);
    private CandidateMatchServiceImpl service;
    private int counter = 1000;

    @BeforeEach
    void setUp() {
        when(settings.current()).thenReturn(POLICY);
        when(actors.current()).thenReturn(new Actor("user_b", PrincipalType.SELLER_MEMBER, Optional.of("org_b"), Set.of(),
                Set.of(CatalogPermissions.WRITE)));
        when(categories.findById(RICE)).thenReturn(Optional.of(node(RICE, "Grocery › Pantry › Rice")));
        when(categories.findById(SHOES)).thenReturn(Optional.of(node(SHOES, "Fashion › Men › Shoes")));
        when(eligibility.evaluate(any(), any(), any())).thenAnswer(i -> {
            Product p = i.getArgument(0);
            Variant v = i.getArgument(1);
            return new Eligibility(v.atin().value(), p.atin().value(), true, List.of(), List.of(ItemCondition.NEW), "org_a");
        });
        service = new CandidateMatchServiceImpl(recall, products, categories, eligibility, settings, decisions, limiter,
                thresholds, actors, clock);
    }

    private static CategoryNode node(UUID id, String path) {
        return new CategoryNode(id, null, path.substring(path.lastIndexOf('›') + 2), CategoryLevel.CATEGORY, null, path, 0, true);
    }

    private Product page(String title, String brand, UUID category, ProductStatus status, String imageUrl) {
        Variant v = new Variant(UUID.randomUUID(), new Atin(String.format("AT%08d", ++counter)), "SKU-" + counter, null, true,
                Map.of(), 899, "USD", 5);
        List<ProductImage> images = imageUrl == null ? List.of()
                : List.of(new ProductImage(imageUrl, 1200, 1200, 1000, "image/jpeg", ""));
        Map<String, String> attrs = brand == null ? Map.of() : Map.of("brand", brand);
        return new Product(UUID.randomUUID(), new Atin(String.format("AT%08d", ++counter)),
                new ListingOwner(ListingOwnerKind.SELLER, "org_a"), category, null, "grocery", title, attrs, images, "",
                List.of(v), status, null, NOW, NOW, 0);
    }

    private Product live(String title, String brand, UUID category) {
        return page(title, brand, category, ProductStatus.LIVE, "https://cdn.example.com/" + (++counter) + ".jpg");
    }

    private void index(Product... pages) {
        List<String> atins = new ArrayList<>();
        for (Product p : pages) {
            atins.add(p.atin().value());
            when(products.findByAtin(p.atin())).thenReturn(Optional.of(p));
        }
        when(recall.recall(anyString(), anyInt())).thenReturn(atins);
    }

    private CandidateResult search(String query, UUID category) {
        return service.candidates(new CandidateCommand(query, category, null));
    }

    @Test
    void returnsLiveBestMatchesFirstWithTheirSizeInWords() {
        Product exact = live("Tilda Basmati Rice 5kg", "Tilda", RICE);
        Product smaller = live("Tilda Basmati Rice 1kg", "Tilda", RICE);
        Product other = live("Leather Oxford Shoes", "Clarks", SHOES);
        index(smaller, other, exact);

        CandidateResult result = search("Tilda basmati rice 5kg", RICE);

        assertThat(result.candidates()).extracting(Candidate::parentAtin).startsWith(exact.atin().value());
        Candidate best = result.candidates().getFirst();
        assertThat(best.kind()).isEqualTo(Kind.SAME_NAME);
        assertThat(best.sizeLabel()).isEqualTo("5 kg");
        assertThat(best.atin()).isEqualTo(exact.variants().getFirst().atin().value());
        assertThat(best.likely()).isTrue();
        assertThat(result.candidates()).extracting(Candidate::parentAtin).doesNotContain(other.atin().value());
        Candidate small = result.candidates().get(1);
        assertThat(small.likely()).isFalse();
    }

    @Test
    void neverShowsPagesThatAreNotLiveOrHaveNoRealPhoto() {
        Product draft = page("Tilda Basmati Rice 5kg", "Tilda", RICE, ProductStatus.IN_REVIEW, "https://cdn.example.com/a.jpg");
        Product noPhoto = page("Tilda Basmati Rice 5kg", "Tilda", RICE, ProductStatus.LIVE, null);
        Product placeholder = page("Tilda Basmati Rice 5kg", "Tilda", RICE, ProductStatus.LIVE,
                "https://cdn.example.com/placeholder.png");
        Product good = live("Tilda Basmati Rice 5kg", "Tilda", RICE);
        index(draft, noPhoto, placeholder, good);

        CandidateResult result = search("Tilda basmati rice 5kg", RICE);

        assertThat(result.candidates()).extracting(Candidate::parentAtin).containsExactly(good.atin().value());
    }

    @Test
    void showsAtMostSixPages() {
        List<Product> pages = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            pages.add(live("Tilda Basmati Rice " + (i + 1) + "kg", "Tilda", RICE));
        }
        index(pages.toArray(new Product[0]));

        assertThat(search("Tilda basmati rice", RICE).candidates()).hasSize(6);
    }

    @Test
    void suggestsTheCategoryMostResultsShareWhenTheSellerGaveNone() {
        index(live("Tilda Basmati Rice 5kg", "Tilda", RICE), live("Royal Basmati Rice 5kg", "Royal", RICE));

        CandidateResult result = search("basmati rice 5kg", null);

        assertThat(result.suggestedCategory().id()).isEqualTo(RICE);
        assertThat(result.category()).isNull();
        assertThat(search("basmati rice 5kg", RICE).suggestedCategory()).isNull();
    }

    @Test
    void aNameThatIsTooShortIsNotSearched() {
        CandidateResult result = search("ri", null);

        assertThat(result.tooShort()).isTrue();
        assertThat(result.candidates()).isEmpty();
        verify(recall, never()).recall(anyString(), anyInt());
    }

    @Test
    void whenTheSearchIndexIsDownTheAnswerIsNoSuggestionsNotAGuess() {
        when(recall.recall(anyString(), anyInt())).thenThrow(new RecallUnavailable("down", null));

        CandidateResult result = search("tilda basmati rice", RICE);

        assertThat(result.searchUnavailable()).isTrue();
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void anOrganisationThatSearchesTooOftenIsLimited() {
        index(live("Tilda Basmati Rice 5kg", "Tilda", RICE));
        for (int i = 0; i < 5; i++) {
            search("tilda basmati rice", RICE);
        }

        assertThatThrownBy(() -> search("tilda basmati rice", RICE)).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("catalog.match-rate-limited");
    }

    @Test
    void anAnswerCarriesNothingAboutAnotherSeller() {
        List<String> names = Arrays.stream(Candidate.class.getRecordComponents()).map(RecordComponent::getName).toList();

        assertThat(names).noneMatch(n -> n.toLowerCase().contains("owner") || n.toLowerCase().contains("seller")
                || n.toLowerCase().contains("price") || n.toLowerCase().contains("stock") || n.toLowerCase().contains("org"));
    }

    @Test
    void sellingAPageThatStatesASizeNeedsTheSizeConfirmed() {
        Product exact = live("Tilda Basmati Rice 5kg", "Tilda", RICE);
        index(exact);
        String atin = exact.variants().getFirst().atin().value();

        assertThatThrownBy(() -> service.decide(new DecisionCommand(Choice.SELL_EXISTING, "tilda basmati rice 5kg", RICE, atin,
                false, null))).isInstanceOf(BusinessRuleException.class).extracting("code")
                .isEqualTo("catalog.match-size-unconfirmed");
        verify(decisions, never()).insert(any());

        DecisionResult ok = service.decide(new DecisionCommand(Choice.SELL_EXISTING, "tilda basmati rice 5kg", RICE, atin, true,
                null));

        assertThat(ok.canOffer()).isTrue();
        assertThat(ok.atin()).isEqualTo(atin);
        ArgumentCaptor<Decision> stored = ArgumentCaptor.forClass(Decision.class);
        verify(decisions).insert(stored.capture());
        assertThat(stored.getValue().choice()).isEqualTo("SELL_EXISTING");
        assertThat(stored.getValue().sellerOrgId()).isEqualTo("org_b");
        assertThat(stored.getValue().reviewState()).isEqualTo("NONE");
    }

    @Test
    void sellingAPageThatWasNotInTheListIsRefused() {
        index(live("Tilda Basmati Rice 5kg", "Tilda", RICE));
        Product hidden = live("Leather Oxford Shoes", "Clarks", SHOES);

        assertThatThrownBy(() -> service.decide(new DecisionCommand(Choice.SELL_EXISTING, "tilda basmati rice 5kg", RICE,
                hidden.variants().getFirst().atin().value(), true, null))).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("catalog.match-choice-not-shown");
    }

    @Test
    void sellingABlockedPageAnswersWithTheBlockersNotAnError() {
        Product exact = live("Tilda Basmati Rice 5kg", "Tilda", RICE);
        index(exact);
        doReturn(new Eligibility(exact.variants().getFirst().atin().value(), exact.atin().value(), false,
                List.of(new Blocker("offers.selling-approval-required", "Approval needed")), List.of(ItemCondition.NEW),
                "org_a")).when(eligibility).evaluate(any(), any(), any());

        DecisionResult result = service.decide(new DecisionCommand(Choice.SELL_EXISTING, "tilda basmati rice 5kg", RICE,
                exact.variants().getFirst().atin().value(), true, null));

        assertThat(result.canOffer()).isFalse();
        assertThat(result.blockers()).extracting(Blocker::code).containsExactly("offers.selling-approval-required");
    }

    @Test
    void noneOfTheseNextToALikelyPageQueuesAPossibleDuplicateForStaff() {
        Product likely = live("Tilda Basmati Rice 5kg", "Tilda", RICE);
        index(likely);

        DecisionResult result = service.decide(new DecisionCommand(Choice.NONE_OF_THESE, "tilda basmati rice 5kg", RICE, null,
                false, null));

        assertThat(result.possibleDuplicateQueued()).isTrue();
        ArgumentCaptor<Decision> stored = ArgumentCaptor.forClass(Decision.class);
        verify(decisions).insert(stored.capture());
        assertThat(stored.getValue().reviewState()).isEqualTo("OPEN");
        assertThat(stored.getValue().likelyDuplicateOf()).isEqualTo(likely.atin().value());
        assertThat(stored.getValue().shown()).contains(likely.atin().value());
    }

    @Test
    void noneOfTheseWhenNothingLikelyWasShownIsOnlyRecorded() {
        index(live("Tilda Basmati Rice 1kg", "Tilda", RICE));

        DecisionResult result = service.decide(new DecisionCommand(Choice.NONE_OF_THESE, "tilda basmati rice 5kg", RICE, null,
                false, null));

        assertThat(result.possibleDuplicateQueued()).isFalse();
        ArgumentCaptor<Decision> stored = ArgumentCaptor.forClass(Decision.class);
        verify(decisions).insert(stored.capture());
        assertThat(stored.getValue().reviewState()).isEqualTo("NONE");
        assertThat(stored.getValue().likelyDuplicateOf()).isNull();
    }

    @Test
    void noneOfTheseIsLimitedPerDay() {
        when(recall.recall(anyString(), anyInt())).thenReturn(List.of());
        when(decisions.countNoneSince(anyString(), any())).thenReturn(2L);

        assertThatThrownBy(() -> service.decide(new DecisionCommand(Choice.NONE_OF_THESE, "tilda basmati rice 5kg", RICE, null,
                false, null))).isInstanceOf(BusinessRuleException.class).extracting("code")
                .isEqualTo("catalog.match-rate-limited");
    }

    @Test
    void aBulkRowLinksOnlyOnAnExactNormalisedNameBrandAndSize() {
        Product exact = live("Tilda Basmati Rice 5kg", "Tilda", RICE);
        index(exact);

        RowMatch row = service.matchRow("org_b", "TILDA basmati  rice", "tilda", RICE);
        assertThat(row.kind()).isNotEqualTo(RowKind.LINKED);

        RowMatch same = service.matchRow("org_b", "Tilda Basmati Rice 5 kg", "Tilda", RICE);
        assertThat(same.kind()).isEqualTo(RowKind.LINKED);
        assertThat(same.parentAtin()).isEqualTo(exact.atin().value());
        assertThat(same.atin()).isEqualTo(exact.variants().getFirst().atin().value());
    }

    @Test
    void aBulkRowThatIsOnlySimilarIsHeldWithCandidatesNeverLinked() {
        Product p = live("Tilda Basmati Rice 5kg", "Tilda", RICE);
        Product q = live("Tilda Basmati Rice 1kg", "Tilda", RICE);
        index(p, q);

        RowMatch row = service.matchRow("org_b", "basmati rice 5kg", null, RICE);

        assertThat(row.kind()).isEqualTo(RowKind.HELD);
        assertThat(row.atin()).isNull();
        assertThat(row.candidates()).hasSizeBetween(1, 3);
    }

    @Test
    void aBulkRowWithTwoExactPagesIsHeld() {
        Product p = live("Tilda Basmati Rice 5kg", "Tilda", RICE);
        Product q = live("Tilda Basmati Rice 5kg", "Tilda", RICE);
        index(p, q);

        assertThat(service.matchRow("org_b", "Tilda Basmati Rice 5kg", "Tilda", RICE).kind()).isEqualTo(RowKind.HELD);
    }

    @Test
    void aBulkRowWithNothingCloseCreatesANewPage() {
        index(live("Leather Oxford Shoes", "Clarks", SHOES));

        assertThat(service.matchRow("org_b", "Tilda Basmati Rice 5kg", "Tilda", RICE).kind()).isEqualTo(RowKind.NONE);
    }
}
