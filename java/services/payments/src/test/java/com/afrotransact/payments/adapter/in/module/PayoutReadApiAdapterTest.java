package com.afrotransact.payments.adapter.in.module;

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

import com.afrotransact.payments.api.PayoutReadApi;
import com.afrotransact.payments.adapter.in.web.PayoutEndpointViews;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;

/**
 * PayoutReadApi runs the endpoint behind the MCP tools AS THE SIGNED-IN PERSON: the module that calls needs the delegation scope, the actor in
 * force in the callee is the person (never the calling module, never a service), and failures leave with the HTTP status.
 */
class PayoutReadApiAdapterTest {

    private final PayoutEndpointViews views = mock(PayoutEndpointViews.class);
    private final PayoutReadApi api = new PayoutReadApiAdapter(views, InProcessKit.ACTORS);
    private final EndUserActor person = InProcessKit.user("u-1", "pt", "SELLER_MEMBER", "perms", List.of("payouts:read"));

    private <T> T as(String scope, Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("mcp-server", scope), "payments", call::get);
    }

    @Test
    void returnsWhatTheEndpointReturnsToAModuleThatHoldsTheDelegationScope() {
        when(views.balance("org-1")).thenReturn(Map.of("ok", true));
        assertThat(as(PayoutReadApi.READ_SCOPE, () -> api.balance(person, "org-1"))).isEqualTo(Map.of("ok", true));
    }

    @Test
    void theEndpointRunsAsThePersonNeverAsTheCallingModule() {
        AtomicReference<Actor> seen = new AtomicReference<>();
        when(views.balance("org-1")).thenAnswer(call -> {
            seen.set(InProcessKit.ACTORS.current());
            return Map.of();
        });
        as(PayoutReadApi.READ_SCOPE, () -> api.balance(person, "org-1"));
        assertThat(seen.get().subject()).isEqualTo("u-1");
        assertThat(seen.get().type()).isEqualTo(PrincipalType.SELLER_MEMBER);
        assertThat(seen.get().permissions()).containsExactly("payouts:read");
    }

    @Test
    void aModuleWithoutTheDelegationScopeIsRefusedAndNothingRuns() {
        assertThatThrownBy(() -> as("users:read", () -> api.balance(person, "org-1"))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.code()).isEqualTo("auth.missing-permission");
        });
        verifyNoInteractions(views);
    }

    @Test
    void failuresLeaveWithTheStatusOfTheHttpProblem() {
        when(views.balance("org-1")).thenThrow(new NotFoundException("x.not-found", "no such thing"));
        assertThatThrownBy(() -> as(PayoutReadApi.READ_SCOPE, () -> api.balance(person, "org-1"))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.code()).isEqualTo("x.not-found");
                });
        reset(views);
        when(views.balance("org-1")).thenThrow(new IllegalArgumentException("bad value"));
        assertThatThrownBy(() -> as(PayoutReadApi.READ_SCOPE, () -> api.balance(person, "org-1"))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.balance(person, "org-1")).isInstanceOf(IllegalStateException.class);
    }
}
