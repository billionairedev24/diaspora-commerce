package com.afrotransact.bookings.adapter.in.module;

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

import com.afrotransact.bookings.api.UserServiceSearchApi;
import com.afrotransact.bookings.adapter.in.web.BookingsEndpointViews;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;

/**
 * UserServiceSearchApi runs the endpoint behind the MCP tools AS THE SIGNED-IN PERSON: the module that calls needs the delegation scope, the actor in
 * force in the callee is the person (never the calling module, never a service), and failures leave with the HTTP status.
 */
class UserServiceSearchApiAdapterTest {

    private final BookingsEndpointViews views = mock(BookingsEndpointViews.class);
    private final UserServiceSearchApi api = new UserServiceSearchApiAdapter(views, InProcessKit.ACTORS);
    private final EndUserActor person = InProcessKit.user("u-1", "pt", "CUSTOMER", "perms", List.of("mcp:shopper"));

    private <T> T as(String scope, Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("mcp-server", scope), "bookings", call::get);
    }

    @Test
    void returnsWhatTheEndpointReturnsToAModuleThatHoldsTheDelegationScope() {
        when(views.findServices("braids", "77002")).thenReturn(Map.of("ok", true));
        assertThat(as(UserServiceSearchApi.READ_SCOPE, () -> api.findServices(person, "braids", "77002"))).isEqualTo(Map.of("ok", true));
    }

    @Test
    void theEndpointRunsAsThePersonNeverAsTheCallingModule() {
        AtomicReference<Actor> seen = new AtomicReference<>();
        when(views.findServices("braids", "77002")).thenAnswer(call -> {
            seen.set(InProcessKit.ACTORS.current());
            return Map.of();
        });
        as(UserServiceSearchApi.READ_SCOPE, () -> api.findServices(person, "braids", "77002"));
        assertThat(seen.get().subject()).isEqualTo("u-1");
        assertThat(seen.get().type()).isEqualTo(PrincipalType.CUSTOMER);
        assertThat(seen.get().permissions()).containsExactly("mcp:shopper");
    }

    @Test
    void aModuleWithoutTheDelegationScopeIsRefusedAndNothingRuns() {
        assertThatThrownBy(() -> as("users:read", () -> api.findServices(person, "braids", "77002"))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.code()).isEqualTo("auth.missing-permission");
        });
        verifyNoInteractions(views);
    }

    @Test
    void failuresLeaveWithTheStatusOfTheHttpProblem() {
        when(views.findServices("braids", "77002")).thenThrow(new NotFoundException("x.not-found", "no such thing"));
        assertThatThrownBy(() -> as(UserServiceSearchApi.READ_SCOPE, () -> api.findServices(person, "braids", "77002"))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.code()).isEqualTo("x.not-found");
                });
        reset(views);
        when(views.findServices("braids", "77002")).thenThrow(new IllegalArgumentException("bad value"));
        assertThatThrownBy(() -> as(UserServiceSearchApi.READ_SCOPE, () -> api.findServices(person, "braids", "77002"))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.findServices(person, "braids", "77002")).isInstanceOf(IllegalStateException.class);
    }
}
