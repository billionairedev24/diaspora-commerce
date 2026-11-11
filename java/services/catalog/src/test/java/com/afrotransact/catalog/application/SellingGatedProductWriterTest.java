package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import org.junit.jupiter.api.Test;

/** A listing is checked after the writer validated it; a clear one goes live without review, a refused one rolls back. */
class SellingGatedProductWriterTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    private final ProductWriter delegate = mock(ProductWriter.class);
    private final SellingPublishGate gate = mock(SellingPublishGate.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ProductEvents events = mock(ProductEvents.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ListingOwner owner = new ListingOwner(ListingOwnerKind.SELLER, "org_1");
    private final UUID categoryId = UUID.randomUUID();
    private final CreateProductCommand command = mock(CreateProductCommand.class);

    private SellingGatedProductWriter writer(boolean autoPublish) {
        when(gate.enabled()).thenReturn(true);
        return new SellingGatedProductWriter(delegate, gate, products, categories, events, clock, autoPublish);
    }

    private Product product(ProductStatus status, TaxStatus tax) {
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT00000011"), "SKU-1", null, true, Map.of(), 100, "USD", 1,
                null, List.of(), false, new VariantTax(Map.of(), tax, List.of(), null));
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), owner, categoryId, null, "packaged-snacks",
                "Plantain chips 8 oz", Map.of(), List.<ProductImage>of(), "", List.of(v), status, null, NOW, NOW, 0);
    }

    private void category() {
        when(categories.findById(categoryId)).thenReturn(Optional.of(new CategoryNode(categoryId, null, "packaged-snacks",
                "Packaged snacks", CategoryLevel.CATEGORY, null, "Packaged snacks", 0, true, 0, null, null, Set.of(),
                "packaged-snacks", "ACTIVE")));
    }

    @Test
    void aListingTheSellerMayNotSellIsRefusedAfterValidationSoTheTransactionRollsBack() {
        Product created = product(ProductStatus.IN_REVIEW, TaxStatus.READY);
        when(delegate.create(owner, command)).thenReturn(created);
        doThrow(new SellingApprovalRequired("APPLY", "You need approval", List.of(), "/selling-approvals"))
                .when(gate).requireApprovable(created);
        assertThatThrownBy(() -> writer(true).create(owner, command)).isInstanceOf(SellingApprovalRequired.class);
        verify(products, never()).update(any());
        verify(events, never()).publish(any(), any());
    }

    @Test
    void aClearListingWithReadyTaxGoesLiveWithoutReview() {
        Product created = product(ProductStatus.IN_REVIEW, TaxStatus.READY);
        when(delegate.create(owner, command)).thenReturn(created);
        category();
        when(products.update(any())).thenAnswer(i -> i.getArgument(0));
        Product result = writer(true).create(owner, command);
        assertThat(result.status()).isEqualTo(ProductStatus.LIVE);
        verify(events).publish(eq(CatalogEvents.PRODUCT_APPROVED), any(Product.class));
    }

    @Test
    void aListingWhoseTaxIsNotReadyStaysForReviewAndAutoPublishCanBeTurnedOff() {
        Product pending = product(ProductStatus.IN_REVIEW, TaxStatus.PENDING_REVIEW);
        when(delegate.create(owner, command)).thenReturn(pending);
        category();
        assertThat(writer(true).create(owner, command).status()).isEqualTo(ProductStatus.IN_REVIEW);
        Product ready = product(ProductStatus.IN_REVIEW, TaxStatus.READY);
        when(delegate.create(owner, command)).thenReturn(ready);
        assertThat(writer(false).create(owner, command).status()).isEqualTo(ProductStatus.IN_REVIEW);
        verify(products, never()).update(any());
    }

    @Test
    void aListingWaitingForARequestedCategoryIsNotPublished() {
        Product waiting = new Product(UUID.randomUUID(), new Atin("AT00000002"), owner, null, UUID.randomUUID(),
                "packaged-snacks", "Plantain chips 8 oz", Map.of(), List.<ProductImage>of(), "", List.of(),
                ProductStatus.PENDING_CATEGORY_APPROVAL, null, NOW, NOW, 0);
        when(delegate.create(owner, command)).thenReturn(waiting);
        assertThat(writer(true).create(owner, command)).isSameAs(waiting);
    }

    @Test
    void aDisabledGateChangesNothing() {
        Product created = product(ProductStatus.IN_REVIEW, TaxStatus.READY);
        when(delegate.create(owner, command)).thenReturn(created);
        SellingGatedProductWriter off = new SellingGatedProductWriter(delegate, gate, products, categories, events, clock,
                true);
        when(gate.enabled()).thenReturn(false);
        assertThat(off.create(owner, command)).isSameAs(created);
        verify(gate, never()).requireApprovable(any());
    }
}
