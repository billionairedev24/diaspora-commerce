package com.afrotransact.catalog.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.application.SearchExportService.SearchExportItem;
import com.afrotransact.catalog.application.SearchExportService.SearchExportPage;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.RatingSummary;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.ListingSuppressionRepository.Decision;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductReviewRepository;
import com.afrotransact.catalog.port.ProductReviewRepository.RatingSnapshot;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchExportServiceTest {

    private static final Instant UPDATED = Instant.parse("2026-10-01T10:00:00Z");

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductReviewRepository reviews = mock(ProductReviewRepository.class);
    private final ListingSuppressionRepository suppressions = mock(ListingSuppressionRepository.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private SearchExportService service;

    @BeforeEach
    void setUp() {
        service = new SearchExportServiceImpl(products, reviews, suppressions, permissions);
    }

    @Test
    void needsCatalogReadAndReadsNothingWithoutIt() {
        when(permissions.require(CatalogPermissions.READ)).thenThrow(new ForbiddenActionException("catalog.forbidden", "catalog:read needed"));

        assertThatThrownBy(() -> service.export(Set.of(), null, null, 10)).isInstanceOf(ForbiddenActionException.class);

        verifyNoInteractions(products, reviews, suppressions);
    }

    @Test
    void exportsLiveProductsAsEventPayloadsWithRatingAndVersion() {
        Product p = product("org_a", ProductStatus.LIVE);
        Instant ratedAt = Instant.parse("2026-10-02T00:00:00Z");
        when(products.findForExport(any(), any(), any(), anyInt())).thenReturn(List.of(p));
        when(reviews.ratingSnapshots(Set.of(p.id())))
                .thenReturn(Map.of(p.id(), new RatingSnapshot(RatingSummary.ofCounts(0, 0, 0, 1, 1), ratedAt)));
        when(suppressions.decisions(Set.of("org_a"))).thenReturn(Map.of());

        SearchExportPage page = service.export(Set.of(), null, null, 10);

        verify(products).findForExport(EnumSet.of(ProductStatus.LIVE), null, null, 11);
        assertThat(page.nextAfter()).isNull();
        SearchExportItem item = page.items().getFirst();
        assertThat(item.product().atin()).isEqualTo("AT0000PRD1");
        assertThat(item.product().status()).isEqualTo("LIVE");
        assertThat(item.product().sellerHidden()).isFalse();
        assertThat(item.version()).isEqualTo(UPDATED);
        assertThat(item.rating().average()).isEqualByComparingTo(new BigDecimal("4.50"));
        assertThat(item.rating().count()).isEqualTo(2);
        assertThat(item.rating().updatedAt()).isEqualTo(ratedAt);
    }

    @Test
    void aProductWithoutPublishedReviewsHasNoRating() {
        Product p = product("org_a", ProductStatus.LIVE);
        when(products.findForExport(any(), any(), any(), anyInt())).thenReturn(List.of(p));
        when(reviews.ratingSnapshots(any())).thenReturn(Map.of());
        when(suppressions.decisions(any())).thenReturn(Map.of());

        assertThat(service.export(Set.of(), null, null, 10).items().getFirst().rating()).isNull();
    }

    @Test
    void aHiddenOwnerMarksTheProductAndVersionsItByTheDecision() {
        Product p = product("org_hidden", ProductStatus.LIVE);
        Instant hiddenAt = UPDATED.plusSeconds(3600);
        when(products.findForExport(any(), any(), any(), anyInt())).thenReturn(List.of(p));
        when(reviews.ratingSnapshots(any())).thenReturn(Map.of());
        when(suppressions.decisions(any())).thenReturn(Map.of("org_hidden", new Decision(true, hiddenAt)));

        SearchExportItem item = service.export(Set.of(), null, null, 10).items().getFirst();

        assertThat(item.product().sellerHidden()).isTrue();
        assertThat(item.version()).isEqualTo(hiddenAt);
    }

    @Test
    void aRestoredOwnerIsNotHiddenButKeepsTheLaterDecisionTimeAsVersion() {
        Product p = product("org_back", ProductStatus.LIVE);
        Instant restoredAt = UPDATED.plusSeconds(60);
        when(products.findForExport(any(), any(), any(), anyInt())).thenReturn(List.of(p));
        when(reviews.ratingSnapshots(any())).thenReturn(Map.of());
        when(suppressions.decisions(any())).thenReturn(Map.of("org_back", new Decision(false, restoredAt)));

        SearchExportItem item = service.export(Set.of(), null, null, 10).items().getFirst();

        assertThat(item.product().sellerHidden()).isFalse();
        assertThat(item.version()).isEqualTo(restoredAt);
    }

    @Test
    void anOlderDecisionNeverLowersTheVersion() {
        Product p = product("org_old", ProductStatus.LIVE);
        when(products.findForExport(any(), any(), any(), anyInt())).thenReturn(List.of(p));
        when(reviews.ratingSnapshots(any())).thenReturn(Map.of());
        when(suppressions.decisions(any())).thenReturn(Map.of("org_old", new Decision(false, UPDATED.minusSeconds(5))));

        assertThat(service.export(Set.of(), null, null, 10).items().getFirst().version()).isEqualTo(UPDATED);
    }

    @Test
    void pagesByIdAndTellsTheNextCursorOnlyWhenMoreFollow() {
        Product a = product("org_a", ProductStatus.PAUSED);
        Product b = product("org_a", ProductStatus.PAUSED);
        Product c = product("org_a", ProductStatus.PAUSED);
        UUID after = UUID.randomUUID();
        Instant since = UPDATED.minusSeconds(1);
        when(products.findForExport(eq(Set.of(ProductStatus.PAUSED)), eq(after), eq(since), eq(3)))
                .thenReturn(List.of(a, b, c));
        when(reviews.ratingSnapshots(any())).thenReturn(Map.of());
        when(suppressions.decisions(any())).thenReturn(Map.of());

        SearchExportPage page = service.export(Set.of(ProductStatus.PAUSED), after, since, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextAfter()).isEqualTo(b.id());
    }

    @Test
    void clampsThePageSize() {
        when(products.findForExport(any(), isNull(), isNull(), anyInt())).thenReturn(List.of());
        when(reviews.ratingSnapshots(any())).thenReturn(Map.of());
        when(suppressions.decisions(any())).thenReturn(Map.of());

        service.export(Set.of(), null, null, 100_000);
        service.export(Set.of(), null, null, 0);

        verify(products).findForExport(any(), isNull(), isNull(), eq(SearchExportService.MAX_PAGE_SIZE + 1));
        verify(products).findForExport(any(), isNull(), isNull(), eq(2));
    }

    private static Product product(String orgId, ProductStatus status) {
        String suffix = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT0000V" + suffix.substring(0, 3)), "SKU-1", null, true,
                Map.of("Net weight", "1 lb"), 899, "USD", 4);
        return new Product(UUID.randomUUID(), new Atin("AT0000PRD1"),
                new ListingOwner(ListingOwnerKind.SELLER, orgId), UUID.randomUUID(), null, "grocery",
                "Ofada Rice, Destoned, 5 lb", Map.of("brand", "Mama Tee"), List.of(), "Net weight", List.of(v), status,
                null, UPDATED.minusSeconds(86_400), UPDATED, 0);
    }
}
