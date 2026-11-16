package com.afrotransact.taxreturns.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.taxreturns.api.ShopperReturnsApi;
import com.afrotransact.taxreturns.application.ReturnSearch;
import com.afrotransact.taxreturns.application.ReturnService;
import com.afrotransact.taxreturns.domain.returns.RefundState;
import com.afrotransact.taxreturns.domain.returns.ReturnMethod;
import com.afrotransact.taxreturns.domain.returns.ReturnReason;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaLine;
import com.afrotransact.taxreturns.domain.returns.RmaStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The shopper-returns API runs tax-returns' own search as the signed-in person with no filter, for a module that holds the delegation scope. */
class ShopperReturnsApiAdapterTest {

    private final List<String> actorsInForce = new ArrayList<>();
    private final ReturnService returns = mock(ReturnService.class);
    private final ShopperReturnsApi api = new ShopperReturnsApiAdapter(returns, () -> {
        var actor = InProcessKit.ACTORS.current();
        actorsInForce.add(actor.subject() + "/" + actor.type());
        return actor;
    });
    private final EndUserActor shopper = InProcessKit.user("customer-1", "pt", "CUSTOMER");

    private <T> T asAiGateway(Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("ai-gateway", ShopperReturnsApi.CALLER_SCOPE), "tax-returns", call);
    }

    @Test
    void listsThePersonsReturnsWithoutTheHandoverCode() {
        Rma rma = mock(Rma.class);
        RmaLine line = mock(RmaLine.class);
        Instant created = Instant.parse("2026-10-20T10:00:00Z");
        when(line.title()).thenReturn("Garri 2kg");
        when(line.quantity()).thenReturn(2);
        when(line.reason()).thenReturn(ReturnReason.DAMAGED);
        when(rma.number()).thenReturn("RMA-1A2B3C4D");
        when(rma.orderNumber()).thenReturn("AT-2026-000123");
        when(rma.status()).thenReturn(RmaStatus.REQUESTED);
        when(rma.method()).thenReturn(ReturnMethod.DROP_OFF);
        when(rma.refundState()).thenReturn(RefundState.NONE);
        when(rma.estimateMinor()).thenReturn(2599L);
        when(rma.currency()).thenReturn("USD");
        when(rma.createdAt()).thenReturn(created);
        when(rma.lines()).thenReturn(List.of(line));
        when(returns.search(any())).thenReturn(List.of(rma));
        List<ShopperReturnsApi.ReturnSummary> out = asAiGateway(() -> api.mine(shopper, 50));
        assertThat(out).containsExactly(new ShopperReturnsApi.ReturnSummary("RMA-1A2B3C4D", "AT-2026-000123", "REQUESTED",
                "DROP_OFF", "NONE", 2599, "USD", null, created, List.of(new ShopperReturnsApi.ReturnLine("Garri 2kg", 2,
                "DAMAGED", null))));
        assertThat(actorsInForce).containsExactly("customer-1/CUSTOMER");
        ArgumentCaptor<ReturnSearch> search = ArgumentCaptor.forClass(ReturnSearch.class);
        verify(returns).search(search.capture());
        assertThat(search.getValue()).isEqualTo(new ReturnSearch(null, null, null, 50));
        assertThat(ShopperReturnsApi.ReturnSummary.class.getRecordComponents()).extracting(c -> c.getName())
                .noneMatch(n -> n.toLowerCase().contains("handover"));
    }

    @Test
    void taxReturnsOwnRefusalsKeepTheirStatus() {
        when(returns.search(any())).thenThrow(new ReturnRuleViolation("returns.bad", "bad"));
        assertThatThrownBy(() -> asAiGateway(() -> api.mine(shopper, 50))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(422);
            assertThat(e.code()).isEqualTo("returns.bad");
        });
    }

    @Test
    void aModuleWithoutTheDelegationScopeIsRefusedBeforeAnyRead() {
        assertThatThrownBy(() -> InProcessCalls.as(InProcessKit.caller("ai-gateway", "tax:read"), "tax-returns",
                () -> api.mine(shopper, 50))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("auth.missing-permission");
                });
        verifyNoInteractions(returns);
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.mine(shopper, 50)).isInstanceOf(IllegalStateException.class);
    }
}
