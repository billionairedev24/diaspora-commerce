package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.SellerScopes;
import com.afrotransact.catalog.port.SellerScopes.OrgScope;
import com.afrotransact.catalog.port.SellerScopes.ScopeUnavailable;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;

/** Who is restricted, who is exempt, and what happens when seller-service cannot answer. */
class SellerScopeGuardImplTest {

    private final SellerScopes sellers = mock(SellerScopes.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final SellerScopeGuardImpl guard = new SellerScopeGuardImpl(sellers, categories,
            new RetailAwareOwnerResolver("org_retail"));
    private final CategoryNode food = new CategoryNode(UUID.randomUUID(), null, "groceries", "Groceries",
            CategoryLevel.DEPARTMENT, null, "Groceries", 0, true, 0, null, null);

    private static Actor member(String org) {
        return new Actor("user", PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"), Set.of());
    }

    @Test
    void aSellerGetsTheDepartmentsSellerServiceRecords() {
        when(categories.findAll()).thenReturn(List.of(food));
        when(sellers.of("org_1")).thenReturn(new OrgScope(false, Set.of("groceries"), List.of()));
        assertThat(guard.scopeOf(member("org_1")).allows(food)).isTrue();
        when(sellers.of("org_2")).thenReturn(new OrgScope(false, Set.of(), List.of()));
        assertThat(guard.scopeOf(member("org_2")).allows(food)).isFalse();
    }

    @Test
    void retailStaffAndExemptOrganisationsAreNotRestricted() {
        assertThat(guard.scopeOf(new ListingOwner(ListingOwnerKind.PLATFORM, "org_retail")).isUnrestricted()).isTrue();
        Actor staff = new Actor("s", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("*"));
        assertThat(guard.scopeOf(staff).isUnrestricted()).isTrue();
        verifyNoInteractions(sellers);
        when(sellers.of("org_x")).thenReturn(new OrgScope(true, Set.of(), List.of()));
        assertThat(guard.scopeOf(new ListingOwner(ListingOwnerKind.SELLER, "org_x")).isUnrestricted()).isTrue();
    }

    @Test
    void whenSellerServiceCannotAnswerTheCatalogRefusesInsteadOfGuessing() {
        when(sellers.of("org_1")).thenThrow(new ScopeUnavailable("down", null));
        assertThatThrownBy(() -> guard.scopeOf(member("org_1"))).isInstanceOf(ScopeUnavailable.class);
    }
}
