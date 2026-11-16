package com.afrotransact.trustops.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.ai.api.TriageApi;
import com.afrotransact.catalog.api.ModerationApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.api.OrderLookupApi;
import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.TriageHint;
import com.afrotransact.trustops.port.CatalogModerationClient;
import com.afrotransact.trustops.port.CatalogReviewClient;
import com.afrotransact.trustops.port.OrdersClient;
import com.afrotransact.trustops.port.TriageClient;
import com.afrotransact.trustops.port.UpstreamException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Trust-ops reaches catalog (moderation, reviews), orders (lookup) and ai-gateway (triage) as its own principal and fails like the HTTP clients. */
class InProcessModerationEdgesTest {

    private static final CallerPrincipal TRUST_OPS = new CallerPrincipal("trust-ops", "trust-ops-service",
            Set.of("catalog:moderate", "orders:lookup", "ai:triage"));

    private final ModerationApi moderation = Mockito.mock(ModerationApi.class);
    private final OrderLookupApi lookup = Mockito.mock(OrderLookupApi.class);
    private final TriageApi triage = Mockito.mock(TriageApi.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final ModuleUpstream upstream;

    InProcessModerationEdgesTest() {
        beans.registerSingleton("moderation", moderation);
        beans.registerSingleton("lookup", lookup);
        beans.registerSingleton("triage", triage);
        beans.registerSingleton("callers", (CallerPrincipals) module -> TRUST_OPS);
        upstream = new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class));
    }

    private CatalogModerationClient products() {
        return new InProcessCatalogModerationClient(beans.getBeanProvider(ModerationApi.class), upstream, beans);
    }

    private CatalogReviewClient reviews() {
        return new InProcessCatalogReviewClient(beans.getBeanProvider(ModerationApi.class), upstream, beans);
    }

    @Test
    void productDecisionsGoThroughCatalogAsTrustOpsWithNotesCutLikeTheHttpClient() {
        UUID id = UUID.randomUUID();
        Mockito.doAnswer(call -> {
            assertThat(InProcessCalls.require().clientId()).isEqualTo("trust-ops-service");
            return null;
        }).when(moderation).approveProduct(id);
        products().approve(id);
        products().reject(id, "n".repeat(2000));
        Mockito.verify(moderation).rejectProduct(Mockito.eq(id), Mockito.argThat(n -> n.length() == 1000));
        products().takeDown(id, ModerationReasonCode.COUNTERFEIT, null);
        Mockito.verify(moderation).takeDownProduct(id, "COUNTERFEIT", "");
    }

    @Test
    void aRefusalKeepsItsStatusAndCodeAndACrashIsUnavailable() {
        UUID id = UUID.randomUUID();
        Mockito.doThrow(new ModuleApiException(409, "catalog.invalid-transition", "not in review")).when(moderation)
                .approveProduct(id);
        assertThatThrownBy(() -> products().approve(id)).isInstanceOfSatisfying(UpstreamException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("catalog.invalid-transition");
            assertThat(e.rejected()).isTrue();
        });
        Mockito.doThrow(new ModuleApiException(403, "auth.missing-permission", "no")).when(moderation).rejectProduct(id, "x");
        assertThatThrownBy(() -> products().reject(id, "x")).isInstanceOfSatisfying(UpstreamException.class,
                e -> assertThat(e.status()).isEqualTo(403));
        Mockito.doThrow(new IllegalStateException("password=hunter2")).when(moderation).takeDownProduct(id, "ABUSIVE", "");
        assertThatThrownBy(() -> products().takeDown(id, ModerationReasonCode.ABUSIVE, null))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.status()).isZero();
                    assertThat(e.getMessage()).doesNotContain("hunter2");
                });
    }

    @Test
    void reviewDecisionsGoThroughCatalogWithNotesCutAtFiveHundred() {
        UUID id = UUID.randomUUID();
        reviews().approve(id, "n".repeat(900));
        Mockito.verify(moderation).approveReview(Mockito.eq(id), Mockito.argThat(n -> n.length() == 500));
        reviews().reject(id, ModerationReasonCode.ABUSIVE, "no");
        reviews().remove(id, ModerationReasonCode.PERSONAL_INFORMATION, null);
        Mockito.verify(moderation).rejectReview(id, "ABUSIVE", "no");
        Mockito.verify(moderation).removeReview(id, "PERSONAL_INFORMATION", null);
    }

    @Test
    void anOrderIsFoundByItsFactsAndAnUnknownOneIsEmpty() {
        OrderLookupApi.Line line = new OrderLookupApi.Line("l1", 1, "AT1", "AT1", "Rice", "FOOD", "grain", false, 1, 100, 0, 0, 0,
                0, null, null, null, null, null, null, null);
        OrderLookupApi.Shipment shipment = new OrderLookupApi.Shipment("s1", 1, "org_a", "Seller", "SELLER_FULFILLED", "CARRIER",
                null, "DELIVERED", false, 0, 100, null, null, null, null, null, true, null, List.of(line));
        Mockito.when(lookup.lookup("o1")).thenReturn(new OrderLookupApi.OrderView("o1", "AT-1", "cust", "DELIVERED", "USD", "p1",
                null, 100, 0, 0, 0, 100, null, List.of(shipment)));
        Mockito.when(lookup.lookup("nope")).thenThrow(new ModuleApiException(404, "orders.not-found", "no such order"));
        Mockito.when(lookup.lookup("denied")).thenThrow(new ModuleApiException(403, "auth.missing-permission", "no"));
        OrdersClient client = new InProcessOrdersClient(beans.getBeanProvider(OrderLookupApi.class), upstream, beans);
        assertThat(client.order("o1")).get().satisfies(o -> {
            assertThat(o.customerId()).isEqualTo("cust");
            assertThat(o.paymentId()).isEqualTo("p1");
            assertThat(o.lineIds("org_a", null)).containsExactly("l1");
        });
        assertThat(client.order("nope")).isEmpty();
        assertThatThrownBy(() -> client.order("denied")).isInstanceOfSatisfying(UpstreamException.class,
                e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void triageMapsTheReportAndKeepsBudgetAndModelRefusals() {
        TrustOpsProperties props = new TrustOpsProperties(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
        Mockito.when(triage.triage("LISTING", "p1", Map.of("title", "x"))).thenReturn(new TriageApi.Report("HIGH", "risky",
                List.of(new TriageApi.Flag("COUNTERFEIT_RISK", "looks fake", List.of("title"))), "m1"));
        TriageClient client = new InProcessTriageClient(beans.getBeanProvider(TriageApi.class), upstream, props, beans);
        Optional<TriageHint> hint = client.triage("LISTING", "p1", Map.of("title", "x"));
        assertThat(hint).get().satisfies(h -> {
            assertThat(h.highRisk()).isTrue();
            assertThat(h.flags()).singleElement().satisfies(f -> assertThat(f.citedFields()).containsExactly("title"));
        });
        Mockito.when(triage.triage("LISTING", "p2", Map.of())).thenThrow(
                new ModuleApiException(429, "ai.budget-exceeded", "daily AI budget spent"));
        assertThatThrownBy(() -> client.triage("LISTING", "p2", Map.of())).isInstanceOfSatisfying(UpstreamException.class, e -> {
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.code()).isEqualTo("ai.budget-exceeded");
        });
        Mockito.when(triage.triage("LISTING", "p3", Map.of())).thenThrow(
                new ModuleApiException(503, "ai.model-unavailable", "none"));
        assertThatThrownBy(() -> client.triage("LISTING", "p3", Map.of())).isInstanceOfSatisfying(UpstreamException.class,
                e -> assertThat(e.status()).isEqualTo(503));
    }

    @Test
    void triageIsEmptyAndNothingIsCalledWhenSwitchedOff() {
        TrustOpsProperties off = new TrustOpsProperties(null, null, null, null, null, null,
                new TrustOpsProperties.Ai(false, null, null), null, null, null, null, null, null, null);
        TriageClient client = new InProcessTriageClient(beans.getBeanProvider(TriageApi.class), upstream, off, beans);
        assertThat(client.triage("LISTING", "p1", Map.of())).isEmpty();
        Mockito.verifyNoInteractions(triage);
    }

    @Test
    void startingWithoutTheCalleeNamesBothModules() {
        DefaultListableBeanFactory none = new DefaultListableBeanFactory();
        none.registerSingleton("callers", (CallerPrincipals) module -> TRUST_OPS);
        ModuleUpstream u = new ModuleUpstream(none.getBeanProvider(CallerPrincipals.class));
        assertThatThrownBy(() -> new InProcessCatalogModerationClient(none.getBeanProvider(ModerationApi.class), u, none))
                .hasMessageContaining("trust-ops").hasMessageContaining("catalog");
        assertThatThrownBy(() -> new InProcessTriageClient(none.getBeanProvider(TriageApi.class), u,
                new TrustOpsProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null), none))
                .hasMessageContaining("ai-gateway");
    }
}
