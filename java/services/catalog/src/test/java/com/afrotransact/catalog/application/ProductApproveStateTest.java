package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductAction;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.tax.TaxDecision;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Approving is only for a product waiting for a decision: an already live product is a plain 409 and nothing about it
 * changes (no new approval, event or audit), and the state machine tells every caller which actions are valid.
 */
class ProductApproveStateTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TaxDecision DECISION = new TaxDecision("EXEMPT", "AT.GROCERY.X", "S001", "0%", "v1", "r");
    private static final ListingOwner OWNER = new ListingOwner(ListingOwnerKind.SELLER, "org_1");

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductEvents events = mock(ProductEvents.class);
    private final SellingPublishGate sellingGate = mock(SellingPublishGate.class);
    private final ProductLiveGate liveGate = mock(ProductLiveGate.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);

    @BeforeEach
    void setUp() {
        Actor staff = mock(Actor.class);
        when(staff.type()).thenReturn(PrincipalType.STAFF);
        when(permissions.require(any())).thenReturn(staff);
    }

    private static Product product(ProductStatus status, String note) {
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT00000011"), "SKU-1", null, true, Map.of(), 100, "USD", 1,
                null, List.of(), false, new VariantTax(Map.of(), TaxStatus.READY, List.of(), DECISION));
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), OWNER, null, null, "packaged-snacks", "Basmati Rice",
                Map.of(), List.<ProductImage>of(), "", List.of(v), status, note, NOW, NOW, 0);
    }

    private ProductServiceImpl service() {
        return new ProductServiceImpl(products, mock(ProductWriter.class), permissions, mock(ResourceAccessChecker.class),
                mock(ActorResolver.class), mock(OwnerResolver.class), events, liveGate, CLOCK, sellingGate);
    }

    @Test
    void approvingALiveProductIsAPlain409AndChangesNothing() {
        Product live = product(ProductStatus.LIVE, null);
        when(products.findById(live.id())).thenReturn(Optional.of(live));

        assertThatThrownBy(() -> service().approve(live.id())).isInstanceOf(IllegalStateException.class)
                .hasMessage("This product is already live.");

        verify(products, never()).update(any());
        verify(events, never()).publish(any(), any());
        verifyNoInteractions(liveGate, sellingGate);
    }

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "IN_REVIEW")
    void noStatusButInReviewCanBeApproved(ProductStatus status) {
        Product p = product(status, null);
        when(products.findById(p.id())).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service().approve(p.id())).isInstanceOf(IllegalStateException.class);

        verify(products, never()).update(any());
        verifyNoInteractions(liveGate);
    }

    @Test
    void approveIsOfferedOnlyWhileInReview() {
        for (ProductStatus status : ProductStatus.values()) {
            assertThat(product(status, null).allowedActions().contains(ProductAction.APPROVE))
                    .as(status.name()).isEqualTo(status == ProductStatus.IN_REVIEW);
        }
    }

    @Test
    void everyActionIsOfferedOnlyWhereTheStateMachineAcceptsIt() {
        assertThat(product(ProductStatus.IN_REVIEW, null).allowedActions())
                .containsExactly(ProductAction.APPROVE, ProductAction.REJECT);
        assertThat(product(ProductStatus.PENDING_CATEGORY_APPROVAL, null).allowedActions())
                .containsExactly(ProductAction.REJECT);
        assertThat(product(ProductStatus.LIVE, null).allowedActions())
                .containsExactly(ProductAction.PAUSE, ProductAction.TAKEDOWN);
        assertThat(product(ProductStatus.PAUSED, null).allowedActions())
                .containsExactly(ProductAction.RESUME, ProductAction.TAKEDOWN);
        assertThat(product(ProductStatus.TAKEN_DOWN, null).allowedActions()).containsExactly(ProductAction.RESTORE);
        assertThat(product(ProductStatus.REJECTED, null).allowedActions()).isEmpty();
        assertThat(product(ProductStatus.MERGED, null).allowedActions()).isEmpty();
        assertThat(product(ProductStatus.PENDING_EXEMPTION, null).allowedActions()).isEmpty();
    }

    @Test
    void aListingClosedByTheTaxGateIsHeldForTaxAndStillApprovableThroughTheGate() {
        Product held = product(ProductStatus.LIVE, null).holdForTax(NOW);
        assertThat(held.heldForTax()).isTrue();
        assertThat(held.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(held.allowedActions()).contains(ProductAction.APPROVE);
        assertThat(product(ProductStatus.IN_REVIEW, "Please add a photo").heldForTax()).isFalse();
        assertThat(product(ProductStatus.LIVE, null).heldForTax()).isFalse();
    }
}
