package com.afrotransact.catalog.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.afrotransact.catalog.api.CatalogUserApi;
import com.afrotransact.catalog.adapter.in.web.CatalogEndpointViews;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;

/**
 * CatalogUserApi runs the endpoint behind the MCP tools AS THE SIGNED-IN PERSON: the module that calls needs the delegation scope, the actor in
 * force in the callee is the person (never the calling module, never a service), and failures leave with the HTTP status.
 */
class CatalogUserApiAdapterTest {

    private final CatalogEndpointViews views = mock(CatalogEndpointViews.class);
    private final CatalogUserApi api = new CatalogUserApiAdapter(views, InProcessKit.ACTORS);
    private final EndUserActor person = InProcessKit.user("u-1", "pt", "SELLER_MEMBER", "perms", List.of("catalog:write"));

    private <T> T as(String scope, Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("mcp-server", scope), "catalog", call::get);
    }

    @Test
    void returnsWhatTheEndpointReturnsToAModuleThatHoldsTheDelegationScope() {
        when(views.product("AT1AAAAAAA")).thenReturn(Map.of("ok", true));
        assertThat(as(CatalogUserApi.READ_SCOPE, () -> api.product(person, "AT1AAAAAAA"))).isEqualTo(Map.of("ok", true));
    }

    @Test
    void theEndpointRunsAsThePersonNeverAsTheCallingModule() {
        AtomicReference<Actor> seen = new AtomicReference<>();
        when(views.product("AT1AAAAAAA")).thenAnswer(call -> {
            seen.set(InProcessKit.ACTORS.current());
            return Map.of();
        });
        as(CatalogUserApi.READ_SCOPE, () -> api.product(person, "AT1AAAAAAA"));
        assertThat(seen.get().subject()).isEqualTo("u-1");
        assertThat(seen.get().type()).isEqualTo(PrincipalType.SELLER_MEMBER);
        assertThat(seen.get().permissions()).containsExactly("catalog:write");
    }

    @Test
    void aModuleWithoutTheDelegationScopeIsRefusedAndNothingRuns() {
        assertThatThrownBy(() -> as("users:read", () -> api.product(person, "AT1AAAAAAA"))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.code()).isEqualTo("auth.missing-permission");
        });
        verifyNoInteractions(views);
    }

    @Test
    void failuresLeaveWithTheStatusOfTheHttpProblem() {
        when(views.product("AT1AAAAAAA")).thenThrow(new NotFoundException("x.not-found", "no such thing"));
        assertThatThrownBy(() -> as(CatalogUserApi.READ_SCOPE, () -> api.product(person, "AT1AAAAAAA"))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.code()).isEqualTo("x.not-found");
                });
        reset(views);
        when(views.product("AT1AAAAAAA")).thenThrow(new IllegalArgumentException("bad value"));
        assertThatThrownBy(() -> as(CatalogUserApi.READ_SCOPE, () -> api.product(person, "AT1AAAAAAA"))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.product(person, "AT1AAAAAAA")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theReadScopeDoesNotAllowTheCategoryRequest() {
        assertThatThrownBy(() -> as(CatalogUserApi.READ_SCOPE, () -> api.requestCategory(person, Map.of("name", "Suya"))))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        verifyNoInteractions(views);
    }

    @Test
    void theCategoryRequestRunsAsThePersonWithTheWriteScope() {
        when(views.requestCategory(Map.of("name", "Suya"))).thenReturn(Map.of("id", "r1"));
        assertThat(as(CatalogUserApi.WRITE_SCOPE, () -> api.requestCategory(person, Map.of("name", "Suya"))))
                .isEqualTo(Map.of("id", "r1"));
    }

    @Test
    void theCategoryRequestQueueIsStaffOnlyAsOverHttp() {
        assertThatThrownBy(() -> as(CatalogUserApi.READ_SCOPE, () -> api.categoryRequests(person, null)))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("auth.staff-only");
                });
        verifyNoInteractions(views);
        EndUserActor staff = InProcessKit.user("s-1", "pt", "STAFF", "perms", List.of("catalog:taxonomy"));
        when(views.categoryRequests(null)).thenReturn(List.of());
        assertThat(as(CatalogUserApi.READ_SCOPE, () -> api.categoryRequests(staff, null))).isEqualTo(List.of());
    }
}
