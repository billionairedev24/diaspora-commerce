package com.afrotransact.identity.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.identity.port.SellerOrgSource;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.seller.api.OrgMirrorApi;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Identity reads the organisation mirror from seller as its own principal; token validation is not involved. */
class InProcessSellerOrgSourceTest {

    private static final CallerPrincipal IDENTITY = new CallerPrincipal("identity", "identity-service",
            Set.of("sellers:directory"));

    private final OrgMirrorApi api = Mockito.mock(OrgMirrorApi.class);
    private final SellerOrgSource source;

    InProcessSellerOrgSourceTest() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("api", api);
        beans.registerSingleton("callers", (CallerPrincipals) module -> IDENTITY);
        source = new InProcessSellerOrgSource(beans.getBeanProvider(OrgMirrorApi.class),
                beans.getBeanProvider(CallerPrincipals.class));
    }

    @Test
    void thePageAsksForApprovedAndSuspendedAsIdentity() {
        Mockito.when(api.page(0, 200, List.of("APPROVED", "SUSPENDED"))).thenAnswer(call -> {
            assertThat(InProcessCalls.require().clientId()).isEqualTo("identity-service");
            return new OrgMirrorApi.OrgMirrorPage(List.of(new OrgMirrorApi.OrgMirror("o1", "SELLER", "PRODUCT", "Legal",
                    "Shop", "US", "APPROVED", List.of("u1"), null, Instant.EPOCH)), 1);
        });
        SellerOrgSource.SellerOrgPage page = source.approvedOrSuspended(0, 200);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().extracting(SellerOrgSource.SellerOrgState::ownerUserIds)
                .isEqualTo(List.of("u1"));
    }

    @Test
    void anUnknownOrgIsEmptyAndARefusalIsAFailure() {
        Mockito.when(api.one("none")).thenReturn(Optional.empty());
        assertThat(source.org("none")).isEmpty();
        Mockito.when(api.one("o1")).thenThrow(new ModuleApiException(403, "auth.missing-permission", "no"));
        assertThatThrownBy(() -> source.org("o1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403");
    }
}
