package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S383: a seller who is not the page owner is refused on every product edit endpoint with 403 {@code catalog.not-your-page}
 * (the page of a live product; a page that is not live stays a 404 so nobody learns that somebody else's draft exists).
 */
class ProductServiceNotYourPageTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductWriter writer = mock(ProductWriter.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final ResourceAccessChecker access = mock(ResourceAccessChecker.class);
    private final ProductLiveGate liveGate = mock(ProductLiveGate.class);
    private ProductServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductServiceImpl(products, writer, permissions, access, mock(ActorResolver.class),
                mock(OwnerResolver.class), mock(ProductEvents.class), liveGate, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(SellingPublishGate.class));
    }

    private static Product page(ProductStatus status) {
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT00000002"), "S-1", "012345678905", false, Map.of(), 899,
                "USD", 5);
        return new Product(UUID.randomUUID(), new Atin("AT00000001"), new ListingOwner(ListingOwnerKind.SELLER, "org_owner"),
                null, null, "custom", "Ankara tote", Map.of(), List.of(), "", List.of(v), status, null, NOW, NOW, 0);
    }

    private Product storedAs(Actor actor, ProductStatus status) {
        Product p = page(status);
        when(permissions.require(anyString())).thenReturn(actor);
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        return p;
    }

    private static Actor seller(String org) {
        return new Actor("user_" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of(), Set.of("catalog:write"));
    }

    @Test
    void anotherSellerGets403OnEveryEditEndpointOfALivePage() {
        Product p = storedAs(seller("org_other"), ProductStatus.LIVE);

        assertThatThrownBy(() -> service.update(p.id(), null)).isInstanceOfSatisfying(ForbiddenActionException.class,
                e -> assertThat(e.code()).isEqualTo("catalog.not-your-page"));
        assertThatThrownBy(() -> service.pause(p.id())).isInstanceOfSatisfying(ForbiddenActionException.class,
                e -> assertThat(e.code()).isEqualTo("catalog.not-your-page"));
        assertThatThrownBy(() -> service.resume(p.id())).isInstanceOfSatisfying(ForbiddenActionException.class,
                e -> assertThat(e.code()).isEqualTo("catalog.not-your-page"));
        assertThatThrownBy(() -> service.setPreorderFlags(p.id(), true, false))
                .isInstanceOfSatisfying(ForbiddenActionException.class,
                        e -> assertThat(e.code()).isEqualTo("catalog.not-your-page"));
        verify(writer, never()).update(any(), any());
        verify(products, never()).update(any());
    }

    @Test
    void theMessageTellsTheSellerWhatToDoInstead() {
        Product p = storedAs(seller("org_other"), ProductStatus.LIVE);

        assertThatThrownBy(() -> service.pause(p.id())).hasMessageContaining("Add your offer")
                .hasMessageContaining("suggest an edit");
    }

    @Test
    void aPageThatIsNotLiveStaysNotFoundForAnotherSeller() {
        Product p = storedAs(seller("org_other"), ProductStatus.IN_REVIEW);

        assertThatThrownBy(() -> service.update(p.id(), null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theOwnerPassesTheOwnershipCheck() {
        Product p = storedAs(seller("org_owner"), ProductStatus.LIVE);
        when(products.update(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.setPreorderFlags(p.id(), true, false).preorderEligible()).isTrue();
    }

    @Test
    void staffMayEditAnyPage() {
        Actor staff = new Actor("staff_1", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("catalog:write"));
        Product p = storedAs(staff, ProductStatus.LIVE);
        when(products.update(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.setPreorderFlags(p.id(), true, false).id()).isEqualTo(p.id());
    }
}
