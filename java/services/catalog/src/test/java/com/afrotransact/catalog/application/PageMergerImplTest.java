package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.PageMerger.Merged;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.AtinRedirectRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** S386: merging two duplicate pages. The loser leaves sale for good, its ATINs redirect, offers are told where to move. */
class PageMergerImplTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private final ProductRepository products = mock(ProductRepository.class);
    private final AtinRedirectRepository redirects = mock(AtinRedirectRepository.class);
    private final ProductEvents productEvents = mock(ProductEvents.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private PageMergerImpl merger;

    @BeforeEach
    void setUp() {
        when(products.update(any())).thenAnswer(i -> i.getArgument(0));
        merger = new PageMergerImpl(products, redirects, productEvents, events);
    }

    @Test
    void theLoserIsMergedAwayItsAtinsRedirectAndOffersAreToldWhereToMove() {
        Product survivor = page("AT10000001", ProductStatus.LIVE, variant("AT10000002", "012345678905", Map.of()));
        Product loser = page("AT20000001", ProductStatus.LIVE, variant("AT20000002", "0012345678905", Map.of()));

        Merged merged = merger.merge(loser, survivor, Map.of(), "staff_1", "Same product, two pages", UUID.randomUUID(), NOW);

        assertThat(merged.loser().status()).isEqualTo(ProductStatus.MERGED);
        assertThat(merged.variantMap()).containsExactly(Map.entry("AT20000002", "AT10000002"));
        ArgumentCaptor<Map<String, String>> pairs = captorOfMap();
        verify(redirects).saveAll(pairs.capture(), eq("staff_1"), eq("Same product, two pages"), eq(NOW));
        assertThat(pairs.getValue()).containsEntry("AT20000001", "AT10000001").containsEntry("AT20000002", "AT10000002");
        verify(redirects).repoint("AT20000001", "AT10000001");
        verify(productEvents).publish(CatalogEvents.PRODUCT_UPDATED, merged.loser());
    }

    @Test
    void theMergeEventNamesTheSurvivorTheVariantMapWhoAndWhy() {
        Product survivor = page("AT10000001", ProductStatus.LIVE, variant("AT10000002", "012345678905", Map.of()));
        Product loser = page("AT20000001", ProductStatus.LIVE, variant("AT20000002", "0012345678905", Map.of()));
        UUID request = UUID.randomUUID();

        merger.merge(loser, survivor, Map.of(), "staff_1", "Same product", request, NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(CatalogEvents.PRODUCT_MERGED), eq(CatalogEvents.AGGREGATE_PRODUCT),
                eq(survivor.id().toString()), payload.capture());
        assertThat(payload.getValue()).containsEntry("loserAtin", "AT20000001").containsEntry("survivorAtin", "AT10000001")
                .containsEntry("survivorProductId", survivor.id().toString()).containsEntry("mergedBy", "staff_1")
                .containsEntry("reason", "Same product").containsEntry("changeRequestId", request.toString())
                .containsEntry("variantMap", Map.of("AT20000002", "AT10000002"));
    }

    @Test
    void variantsAreMappedByBarcodeThenByIdenticalOptions() {
        Product survivor = page("AT10000001", ProductStatus.LIVE,
                variant("AT10000002", "4006381333931", Map.of("Size", "M")),
                variant("AT10000003", null, Map.of("Size", "L")), variant("AT10000004", null, Map.of("Size", "S")));
        Product loser = page("AT20000001", ProductStatus.LIVE,
                variant("AT20000002", "04006381333931", Map.of("Size", "Medium")),   // the same barcode as a 14-digit key
                variant("AT20000003", null, Map.of("Size", "L")));                    // no barcode: identical options

        Merged merged = merger.merge(loser, survivor, Map.of(), "staff_1", "Same product", null, NOW);

        assertThat(merged.variantMap()).containsEntry("AT20000002", "AT10000002").containsEntry("AT20000003", "AT10000003");
    }

    @Test
    void aVariantNamedByStaffWinsOverTheAutomaticMap() {
        Product survivor = page("AT10000001", ProductStatus.LIVE, variant("AT10000002", "012345678905", Map.of("Size", "M")),
                variant("AT10000003", null, Map.of("Size", "L")));
        Product loser = page("AT20000001", ProductStatus.LIVE, variant("AT20000002", "012345678905", Map.of("Size", "M")));

        Merged merged = merger.merge(loser, survivor, Map.of("AT20000002", "AT10000003"), "staff_1", "Staff know better", null,
                NOW);

        assertThat(merged.variantMap()).containsExactly(Map.entry("AT20000002", "AT10000003"));
    }

    @Test
    void aNamedVariantThatIsNotOnTheSurvivorIsRefused() {
        Product survivor = page("AT10000001", ProductStatus.LIVE, variant("AT10000002", "012345678905", Map.of()));
        Product loser = page("AT20000001", ProductStatus.LIVE, variant("AT20000002", null, Map.of()));

        assertThatThrownBy(() -> merger.merge(loser, survivor, Map.of("AT20000002", "AT99999999"), "staff_1", "x y z", null,
                NOW)).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.code()).isEqualTo("catalog.merge-unmapped-variant"));
        verify(products, never()).update(any());
    }

    @Test
    void aVariantThatCannotBeMappedStopsTheMergeBeforeAnythingIsWritten() {
        Product survivor = page("AT10000001", ProductStatus.LIVE, variant("AT10000002", "012345678905", Map.of("Size", "M")),
                variant("AT10000003", null, Map.of("Size", "L")));
        Product loser = page("AT20000001", ProductStatus.LIVE, variant("AT20000002", null, Map.of("Size", "XXL")));

        assertThatThrownBy(() -> merger.merge(loser, survivor, Map.of(), "staff_1", "Same product", null, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.merge-unmapped-variant"));
        verify(products, never()).update(any());
        verify(redirects, never()).saveAll(any(), any(), any(), any());
        verify(events, never()).publish(any(), any(), any(), any());
    }

    @Test
    void twoSingleVariantPagesMergeEvenWithoutAMatchingKey() {
        Product survivor = page("AT10000001", ProductStatus.LIVE, variant("AT10000002", null, Map.of()));
        Product loser = page("AT20000001", ProductStatus.LIVE, variant("AT20000002", null, Map.of()));

        assertThat(merger.merge(loser, survivor, Map.of(), "staff_1", "Same product", null, NOW).variantMap())
                .containsExactly(Map.entry("AT20000002", "AT10000002"));
    }

    @Test
    void aPageCannotBeMergedIntoItselfOrIntoAMergedOrRejectedPage() {
        Product live = page("AT10000001", ProductStatus.LIVE, variant("AT10000002", null, Map.of()));
        Product merged = page("AT20000001", ProductStatus.MERGED, variant("AT20000002", null, Map.of()));
        Product rejected = page("AT30000001", ProductStatus.REJECTED, variant("AT30000002", null, Map.of()));

        assertThatThrownBy(() -> merger.merge(live, live, Map.of(), "s", "x y z", null, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.code()).isEqualTo("catalog.merge-invalid"));
        assertThatThrownBy(() -> merger.merge(live, merged, Map.of(), "s", "x y z", null, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.code()).isEqualTo("catalog.merge-invalid"));
        assertThatThrownBy(() -> merger.merge(merged, live, Map.of(), "s", "x y z", null, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.code()).isEqualTo("catalog.merge-invalid"));
        assertThatThrownBy(() -> merger.merge(live, rejected, Map.of(), "s", "x y z", null, NOW))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.code()).isEqualTo("catalog.merge-invalid"));
    }

    @Test
    void aLoserThatIsRejectedMayStillBeMergedIntoALivePage() {
        Product survivor = page("AT10000001", ProductStatus.LIVE, variant("AT10000002", null, Map.of()));
        Product loser = page("AT30000001", ProductStatus.REJECTED, variant("AT30000002", null, Map.of()));

        assertThat(merger.merge(loser, survivor, Map.of(), "s", "x y z", null, NOW).loser().status())
                .isEqualTo(ProductStatus.MERGED);
    }

    @Test
    void theSurvivorIsNeverStoredOrPutOnSale() {
        Product survivor = page("AT10000001", ProductStatus.IN_REVIEW, variant("AT10000002", null, Map.of()));
        Product loser = page("AT20000001", ProductStatus.LIVE, variant("AT20000002", null, Map.of()));

        Merged merged = merger.merge(loser, survivor, Map.of(), "staff_1", "Same product", null, NOW);

        assertThat(merged.survivor()).isSameAs(survivor);
        assertThat(merged.survivor().status()).isEqualTo(ProductStatus.IN_REVIEW);
        ArgumentCaptor<Product> stored = ArgumentCaptor.forClass(Product.class);
        verify(products).update(stored.capture());
        assertThat(stored.getValue().id()).isEqualTo(loser.id());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> captorOfMap() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private static Variant variant(String atin, String gtin, Map<String, String> options) {
        return new Variant(UUID.randomUUID(), new Atin(atin), "S-" + atin, gtin, gtin == null, options, 899, "USD", 5);
    }

    private static Product page(String atin, ProductStatus status, Variant... variants) {
        return new Product(UUID.randomUUID(), new Atin(atin), new ListingOwner(ListingOwnerKind.SELLER, "org_a"), null, null,
                "custom", "Ankara tote", Map.of(), List.of(), "", List.of(variants), status, null, NOW, NOW, 0);
    }
}
