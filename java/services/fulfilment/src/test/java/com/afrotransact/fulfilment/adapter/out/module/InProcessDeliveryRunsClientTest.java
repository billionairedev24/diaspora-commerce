package com.afrotransact.fulfilment.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.afrotransact.deliveryruns.api.DeliveryRunsApi;
import com.afrotransact.fulfilment.port.DeliveryRunsClient;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.ModuleApiException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Fulfilment plans a run from delivery-runs' published dispatch answer the way the HTTP client reads the endpoint's. */
class InProcessDeliveryRunsClientTest {

    private static final CallerPrincipal FULFILMENT = new CallerPrincipal("fulfilment", "fulfilment-service",
            Set.of("delivery-runs:read"));

    private final DeliveryRunsApi api = Mockito.mock(DeliveryRunsApi.class);
    private final DeliveryRunsClient client;

    InProcessDeliveryRunsClientTest() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("api", api);
        beans.registerSingleton("callers", (CallerPrincipals) module -> FULFILMENT);
        ModuleUpstream upstream = new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class));
        client = new InProcessDeliveryRunsClient(beans.getBeanProvider(DeliveryRunsApi.class), upstream, beans);
    }

    @Test
    void aRunBecomesAPlanWithItsConfirmedOrdersPickupPointAndHub() {
        Mockito.when(api.dispatch("run-1")).thenReturn(new DeliveryRunsApi.RunPlan("run-1", "CONFIRMED", "Bastrop", "TX", null,
                Instant.parse("2026-10-24T15:00:00Z"), Instant.parse("2026-10-24T19:00:00Z"), null, false,
                new DeliveryRunsApi.PickupPoint("Library", "1100 Church St", "Bastrop", "TX", "78602", "Sat 2-5", null), 30.1, -97.3,
                List.of("o1", "o2")));
        DeliveryRunsClient.RunPlan plan = client.dispatch("run-1");
        assertThat(plan.runId()).isEqualTo("run-1");
        assertThat(plan.timeZone()).isEqualTo("America/Chicago");
        assertThat(plan.doorstep()).isFalse();
        assertThat(plan.pickupPoint().name()).isEqualTo("Library");
        assertThat(plan.pickupPoint().notes()).isNull();
        assertThat(plan.origin().lat()).isEqualTo(30.1);
        assertThat(plan.orders()).containsExactly("o1", "o2");
    }

    @Test
    void noHubMeansNoOriginAndAnAnswerWithoutARunIsTheRetryableInvalidResponse() {
        Mockito.when(api.dispatch("run-2")).thenReturn(new DeliveryRunsApi.RunPlan("run-2", "OPEN", "Elgin", "TX", "America/Chicago",
                null, null, null, true, null, null, null, List.of()));
        assertThat(client.dispatch("run-2").origin()).isNull();
        Mockito.when(api.dispatch("run-3")).thenReturn(new DeliveryRunsApi.RunPlan("", "OPEN", "x", "TX", null, null, null, null,
                true, null, null, null, List.of()));
        assertThatThrownBy(() -> client.dispatch("run-3")).isInstanceOfSatisfying(UpstreamFailure.class, f -> {
            assertThat(f.code()).isEqualTo("delivery-runs.invalid-response");
            assertThat(f.retryable()).isTrue();
        });
    }

    @Test
    void aRefusalKeepsItsStatusAndTheStartNamesBothModulesWhenDeliveryRunsIsNotRunning() {
        Mockito.when(api.dispatch("nope")).thenThrow(new ModuleApiException(404, "delivery-runs.run-not-found", "none"));
        assertThatThrownBy(() -> client.dispatch("nope")).isInstanceOfSatisfying(UpstreamFailure.class,
                f -> assertThat(f.status()).isEqualTo(404));
        DefaultListableBeanFactory none = new DefaultListableBeanFactory();
        none.registerSingleton("callers", (CallerPrincipals) module -> FULFILMENT);
        assertThatThrownBy(() -> new InProcessDeliveryRunsClient(none.getBeanProvider(DeliveryRunsApi.class),
                new ModuleUpstream(none.getBeanProvider(CallerPrincipals.class)), none))
                .hasMessageContaining("fulfilment").hasMessageContaining("delivery-runs");
    }
}
