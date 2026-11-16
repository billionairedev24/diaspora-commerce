package com.afrotransact.seller.adapter.in.module;

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

import com.afrotransact.seller.api.SellerUserApi;
import com.afrotransact.seller.adapter.in.web.SellerEndpointViews;
import com.afrotransact.seller.domain.SellerForbidden;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;

/**
 * SellerUserApi runs the endpoint behind the MCP tools AS THE SIGNED-IN PERSON: the module that calls needs the delegation scope, the actor in
 * force in the callee is the person (never the calling module, never a service), and failures leave with the HTTP status.
 */
class SellerUserApiAdapterTest {

    private final SellerEndpointViews views = mock(SellerEndpointViews.class);
    private final SellerUserApi api = new SellerUserApiAdapter(views, InProcessKit.ACTORS);
    private final EndUserActor person = InProcessKit.user("u-1", "pt", "STAFF", "perms", List.of("applications:review"));

    private <T> T as(String scope, Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("mcp-server", scope), "seller", call::get);
    }

    @Test
    void returnsWhatTheEndpointReturnsToAModuleThatHoldsTheDelegationScope() {
        when(views.applicationQueue("IN_REVIEW", 0, 25)).thenReturn(Map.of("ok", true));
        assertThat(as(SellerUserApi.READ_SCOPE, () -> api.applicationQueue(person, "IN_REVIEW", 0, 25))).isEqualTo(Map.of("ok", true));
    }

    @Test
    void theEndpointRunsAsThePersonNeverAsTheCallingModule() {
        AtomicReference<Actor> seen = new AtomicReference<>();
        when(views.applicationQueue("IN_REVIEW", 0, 25)).thenAnswer(call -> {
            seen.set(InProcessKit.ACTORS.current());
            return Map.of();
        });
        as(SellerUserApi.READ_SCOPE, () -> api.applicationQueue(person, "IN_REVIEW", 0, 25));
        assertThat(seen.get().subject()).isEqualTo("u-1");
        assertThat(seen.get().type()).isEqualTo(PrincipalType.STAFF);
        assertThat(seen.get().permissions()).containsExactly("applications:review");
    }

    @Test
    void aModuleWithoutTheDelegationScopeIsRefusedAndNothingRuns() {
        assertThatThrownBy(() -> as("users:read", () -> api.applicationQueue(person, "IN_REVIEW", 0, 25))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.code()).isEqualTo("auth.missing-permission");
        });
        verifyNoInteractions(views);
    }

    @Test
    void failuresLeaveWithTheStatusOfTheHttpProblem() {
        when(views.applicationQueue("IN_REVIEW", 0, 25)).thenThrow(new NotFoundException("x.not-found", "no such thing"));
        assertThatThrownBy(() -> as(SellerUserApi.READ_SCOPE, () -> api.applicationQueue(person, "IN_REVIEW", 0, 25))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.code()).isEqualTo("x.not-found");
                });
        reset(views);
        when(views.applicationQueue("IN_REVIEW", 0, 25)).thenThrow(new IllegalArgumentException("bad value"));
        assertThatThrownBy(() -> as(SellerUserApi.READ_SCOPE, () -> api.applicationQueue(person, "IN_REVIEW", 0, 25))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.applicationQueue(person, "IN_REVIEW", 0, 25)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSellerRuleViolationIs422AndAForbiddenIs403() {
        when(views.accountHealth("org-1")).thenThrow(new SellerForbidden("seller.forbidden", "not yours"));
        assertThatThrownBy(() -> as(SellerUserApi.READ_SCOPE, () -> api.accountHealth(person, "org-1")))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("seller.forbidden");
                });
    }
}
