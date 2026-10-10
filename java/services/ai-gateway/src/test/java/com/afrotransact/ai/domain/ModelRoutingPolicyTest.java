package com.afrotransact.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ModelRoutingPolicyTest {

    static final PriceTable PRICES = new PriceTable(Map.of(
            "strong", new PriceTable.ModelPrice(new BigDecimal("3"), new BigDecimal("15")),
            "medium", new PriceTable.ModelPrice(new BigDecimal("1"), new BigDecimal("5")),
            "cheap", new PriceTable.ModelPrice(new BigDecimal("0.1"), new BigDecimal("0.4"))),
            new PriceTable.ModelPrice(new BigDecimal("15"), new BigDecimal("75")));

    static List<ModelRoute> routes(ModelRoute... overrides) {
        List<ModelRoute> routes = new ArrayList<>();
        for (TaskType t : TaskType.values()) {
            ModelRoute r = new ModelRoute(t, List.of("strong", "medium", "cheap"), Duration.ofSeconds(5), 1,
                    Duration.ofMillis(100));
            for (ModelRoute o : overrides) {
                if (o.task() == t) {
                    r = o;
                }
            }
            routes.add(r);
        }
        return routes;
    }

    @Test
    void candidatesKeepConfiguredFallbackOrder() {
        DefaultModelRoutingPolicy policy = new DefaultModelRoutingPolicy(routes(
                new ModelRoute(TaskType.INTENT, List.of("cheap", " medium ", "cheap"), null, 0, null)), PRICES);
        assertThat(policy.candidates(TaskType.PLANNING, false)).containsExactly("strong", "medium", "cheap");
        assertThat(policy.candidates(TaskType.INTENT, false)).containsExactly("cheap", "medium");
    }

    @Test
    void degradeServesOnlyTheCheapestModelOfTheRoute() {
        DefaultModelRoutingPolicy policy = new DefaultModelRoutingPolicy(routes(), PRICES);
        assertThat(policy.candidates(TaskType.CHAT, true)).containsExactly("cheap");
    }

    @Test
    void retriesOnlyRateLimitsAndServerErrorsUpToTheRouteLimit() {
        DefaultModelRoutingPolicy policy = new DefaultModelRoutingPolicy(routes(), PRICES);
        assertThat(policy.shouldRetry(TaskType.CHAT, FailureKind.RATE_LIMITED, 1)).isTrue();
        assertThat(policy.shouldRetry(TaskType.CHAT, FailureKind.SERVER_ERROR, 1)).isTrue();
        assertThat(policy.shouldRetry(TaskType.CHAT, FailureKind.SERVER_ERROR, 2)).isFalse();
        assertThat(policy.shouldRetry(TaskType.CHAT, FailureKind.TIMEOUT, 1)).isFalse();
        assertThat(policy.shouldRetry(TaskType.CHAT, FailureKind.CLIENT_ERROR, 1)).isFalse();
    }

    @Test
    void statusCodesMapToFailureKinds() {
        assertThat(FailureKind.ofStatus(429)).isEqualTo(FailureKind.RATE_LIMITED);
        assertThat(FailureKind.ofStatus(502)).isEqualTo(FailureKind.SERVER_ERROR);
        assertThat(FailureKind.ofStatus(408)).isEqualTo(FailureKind.TIMEOUT);
        assertThat(FailureKind.ofStatus(400)).isEqualTo(FailureKind.CLIENT_ERROR);
    }

    @Test
    void backoffDoublesAndIsCapped() {
        ModelRoute r = new ModelRoute(TaskType.CHAT, List.of("strong"), null, 3, Duration.ofMillis(200));
        assertThat(r.backoffFor(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(r.backoffFor(2)).isEqualTo(Duration.ofMillis(400));
        assertThat(r.backoffFor(3)).isEqualTo(Duration.ofMillis(800));
        assertThat(new ModelRoute(TaskType.CHAT, List.of("strong"), null, 9, Duration.ofSeconds(2)).backoffFor(9))
                .isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void everyTaskNeedsARoute() {
        List<ModelRoute> missing = routes().subList(1, TaskType.values().length);
        assertThatThrownBy(() -> new DefaultModelRoutingPolicy(missing, PRICES))
                .hasMessageContaining("no model route configured");
        assertThatThrownBy(() -> new ModelRoute(TaskType.CHAT, List.of(" "), null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
