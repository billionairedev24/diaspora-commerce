package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ImportStatus;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.ProductImportRepository.RowResult;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SellerOffers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** S393: the children of a mixed parent group that are not on the page are asked for, never created as a second page. */
class VariantRequestsTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final List<String> HEADER = List.of("Parent SKU", "SKU", "Barcode (GTIN/UPC/EAN)", "Options");

    private final ProductRepository products = mock(ProductRepository.class);
    private final ChangeRequestRepository requests = mock(ChangeRequestRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final CatalogSettingsService settings = mock(CatalogSettingsService.class);
    private final SellerOffers sellerOffers = mock(SellerOffers.class);
    private final ChangeRequestEvents events = mock(ChangeRequestEvents.class);
    private final Product page = mock(Product.class);
    private VariantRequestsImpl impl;

    @BeforeEach
    void setUp() {
        impl = new VariantRequestsImpl(products, requests, categories, settings, sellerOffers, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(page.atin()).thenReturn(new Atin("AT00000001"));
        when(page.id()).thenReturn(UUID.randomUUID());
        when(products.findByAtin(any())).thenReturn(Optional.of(page));
        when(settings.current()).thenReturn(new CatalogPolicy(List.of(), "mpn", 2, 3, 280, 5, List.of(), false, null, null, 1));
        when(requests.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(sellerOffers.hasOpenOffer("org_b", "AT00000001")).thenReturn(true);
    }

    private static ProductImport upload() {
        return new ProductImport(UUID.randomUUID(), "BU-000001", new ListingOwner(ListingOwnerKind.SELLER, "org_b"),
                "user_b", "Ada", "products.csv", SpreadsheetFormat.CSV, ImportMode.CREATE, null, HEADER, List.of(),
                ImportStatus.RUNNING, false, 3, 3, 0, 0, 3, 0, 0, 0, 0, false, NOW, NOW, null, NOW, "abc", null, 0, 0, 0);
    }

    private static StoredRow row(String options) {
        return new StoredRow(4, "variant:4", "A-L", RowStatus.WARNING, List.of(), List.of("PARENT-1", "A-L", "", options),
                RowOutcome.PENDING, null, null);
    }

    @Test
    void aNewVariantRequestIsFiledForThePageWithTheSkuAsItsFieldAndTheRowIsHeldForReview() {
        RowResult r = impl.file(upload(), row("Size: L; Color: Gold"), "AT00000001");

        assertThat(r.outcome()).isEqualTo(RowOutcome.HELD);
        assertThat(r.note()).isEqualTo("This size or colour is not on the page yet. We sent it for review.");
        assertThat(r.atin()).isEqualTo("AT00000001");
        ArgumentCaptor<ProductChangeRequest> filed = ArgumentCaptor.forClass(ProductChangeRequest.class);
        verify(requests).insert(filed.capture());
        assertThat(filed.getValue().type()).isEqualTo(ChangeRequestType.NEW_VARIANT);
        assertThat(filed.getValue().field()).isEqualTo("A-L");
        assertThat(filed.getValue().requestedByOrg()).isEqualTo("org_b");
        assertThat(filed.getValue().payload()).containsKey("options");
        verify(events).created(any(), eq(page), eq(NOW));
    }

    @Test
    void aRowThatNamesNoSizeOrColourIsAPlainFailure() {
        RowResult r = impl.file(upload(), row(""), "AT00000001");

        assertThat(r.outcome()).isEqualTo(RowOutcome.FAILED);
        assertThat(r.note()).startsWith("Say which size or colour this row is");
        verify(requests, never()).insert(any());
    }

    @Test
    void withoutAnOpenOfferOnThePageNothingIsFiled() {
        when(sellerOffers.hasOpenOffer(anyString(), anyString())).thenReturn(false);

        RowResult r = impl.file(upload(), row("Size: L"), "AT00000001");

        assertThat(r.outcome()).isEqualTo(RowOutcome.FAILED);
        assertThat(r.note()).startsWith("Add your offer to this product first");
        verify(requests, never()).insert(any());
    }

    @Test
    void theSameFileAgainDoesNotAskTwice() {
        when(requests.existsOpen(any(), eq(ChangeRequestType.NEW_VARIANT), eq("A-L"), eq("org_b"))).thenReturn(true);

        RowResult r = impl.file(upload(), row("Size: L"), "AT00000001");

        assertThat(r.outcome()).isEqualTo(RowOutcome.HELD);
        verify(requests, never()).insert(any());
    }

    @Test
    void withoutTheSiblingsPageNothingCanBeAsked() {
        RowResult r = impl.file(upload(), row("Size: L"), null);

        assertThat(r.outcome()).isEqualTo(RowOutcome.FAILED);
        verify(requests, never()).insert(any());
    }
}
