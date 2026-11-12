package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;

/** A seller may sell in any department: the guard restricts nobody and asks nobody (no seller-service call to fail). */
class SellerScopeGuardImplTest {

    private final SellerScopeGuardImpl guard = new SellerScopeGuardImpl();
    private final CategoryNode fashion = new CategoryNode(UUID.randomUUID(), null, "fashion", "Fashion",
            CategoryLevel.DEPARTMENT, null, "Fashion", 0, true, 0, null, null);

    private static Actor member(String org) {
        return new Actor("user", PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"), Set.of());
    }

    @Test
    void aSellerIsNotRestrictedToTheDepartmentsItRegisteredFor() {
        assertThat(guard.scopeOf(member("org_food")).isUnrestricted()).isTrue();
        assertThat(guard.scopeOf(member("org_food")).allows(fashion)).isTrue();
        assertThat(guard.scopeOf(new ListingOwner(ListingOwnerKind.SELLER, "org_food")).allows(fashion)).isTrue();
        assertThat(guard.scopeOf(new ListingOwner(ListingOwnerKind.SELLER, "org_food")).allowsPath("Fashion › Women"))
                .isTrue();
    }

    @Test
    void retailAndStaffAreUnrestrictedToo() {
        assertThat(guard.scopeOf(new ListingOwner(ListingOwnerKind.PLATFORM, "org_retail")).isUnrestricted()).isTrue();
        Actor staff = new Actor("s", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("*"));
        assertThat(guard.scopeOf(staff).isUnrestricted()).isTrue();
    }

    @Test
    void thereAreNoDepartmentRequestsToShow() {
        assertThat(guard.orgScopeOf(member("org_food")).requests()).isEmpty();
    }

    @Test
    void aDraftInAnotherDepartmentIsSavedForAnApprovedSeller() {
        PermissionChecker permissions = mock(PermissionChecker.class);
        Actor actor = new Actor("user_1", PrincipalType.SELLER_MEMBER, Optional.of("org_food"), Set.of("seller.owner"),
                Set.of(CatalogPermissions.WRITE));
        when(permissions.require(CatalogPermissions.WRITE)).thenReturn(actor);
        OwnerResolver owners = mock(OwnerResolver.class);
        when(owners.ownerFor(actor)).thenReturn(new ListingOwner(ListingOwnerKind.SELLER, "org_food"));
        ProductDraftRepository drafts = mock(ProductDraftRepository.class);
        ProductDraftServiceImpl service = new ProductDraftServiceImpl(drafts, mock(ProductRepository.class),
                permissions, mock(ResourceAccessChecker.class), owners, guard,
                Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC));
        service.create(null, "Draft", "Fashion › Women", "{}");
        verify(drafts).insert(any());
    }
}
