package com.afrotransact.taxreturns.adapter.in.module;

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

import com.afrotransact.taxreturns.api.TaxReviewReadApi;
import com.afrotransact.taxreturns.adapter.in.web.TaxReviewEndpointViews;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;

/**
 * TaxReviewReadApi runs the endpoint behind the MCP tools AS THE SIGNED-IN PERSON: the module that calls needs the delegation scope, the actor in
 * force in the callee is the person (never the calling module, never a service), and failures leave with the HTTP status.
 */
class TaxReviewReadApiAdapterTest {

    private final TaxReviewEndpointViews views = mock(TaxReviewEndpointViews.class);
    private final TaxReviewReadApi api = new TaxReviewReadApiAdapter(views, InProcessKit.ACTORS);
    private final EndUserActor person = InProcessKit.user("u-1", "pt", "STAFF", "perms", List.of("tax:read"));

    private <T> T as(String scope, Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("mcp-server", scope), "tax-returns", call::get);
    }

    @Test
    void returnsWhatTheEndpointReturnsToAModuleThatHoldsTheDelegationScope() {
        when(views.reviewQueue("PENDING_REVIEW", null, 0, 25)).thenReturn(Map.of("ok", true));
        assertThat(as(TaxReviewReadApi.READ_SCOPE, () -> api.reviewQueue(person, "PENDING_REVIEW", null, 0, 25))).isEqualTo(Map.of("ok", true));
    }

    @Test
    void theEndpointRunsAsThePersonNeverAsTheCallingModule() {
        AtomicReference<Actor> seen = new AtomicReference<>();
        when(views.reviewQueue("PENDING_REVIEW", null, 0, 25)).thenAnswer(call -> {
            seen.set(InProcessKit.ACTORS.current());
            return Map.of();
        });
        as(TaxReviewReadApi.READ_SCOPE, () -> api.reviewQueue(person, "PENDING_REVIEW", null, 0, 25));
        assertThat(seen.get().subject()).isEqualTo("u-1");
        assertThat(seen.get().type()).isEqualTo(PrincipalType.STAFF);
        assertThat(seen.get().permissions()).containsExactly("tax:read");
    }

    @Test
    void aModuleWithoutTheDelegationScopeIsRefusedAndNothingRuns() {
        assertThatThrownBy(() -> as("users:read", () -> api.reviewQueue(person, "PENDING_REVIEW", null, 0, 25))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.code()).isEqualTo("auth.missing-permission");
        });
        verifyNoInteractions(views);
    }

    @Test
    void failuresLeaveWithTheStatusOfTheHttpProblem() {
        when(views.reviewQueue("PENDING_REVIEW", null, 0, 25)).thenThrow(new NotFoundException("x.not-found", "no such thing"));
        assertThatThrownBy(() -> as(TaxReviewReadApi.READ_SCOPE, () -> api.reviewQueue(person, "PENDING_REVIEW", null, 0, 25))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.code()).isEqualTo("x.not-found");
                });
        reset(views);
        when(views.reviewQueue("PENDING_REVIEW", null, 0, 25)).thenThrow(new IllegalArgumentException("bad value"));
        assertThatThrownBy(() -> as(TaxReviewReadApi.READ_SCOPE, () -> api.reviewQueue(person, "PENDING_REVIEW", null, 0, 25))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.reviewQueue(person, "PENDING_REVIEW", null, 0, 25)).isInstanceOf(IllegalStateException.class);
    }
}
