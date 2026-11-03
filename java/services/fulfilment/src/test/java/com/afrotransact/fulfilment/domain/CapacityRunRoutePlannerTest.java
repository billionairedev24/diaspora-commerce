package com.afrotransact.fulfilment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** S264: one route per run, or balanced routes by capacity; drops nearest the hub first; an order is never split. */
class CapacityRunRoutePlannerTest {

    private final RunRoutePlanner planner = new CapacityRunRoutePlanner();

    private static RunDrop drop(String order, int packages, Double meters) {
        return new RunDrop(order, List.of("shp-" + order), packages, meters);
    }

    private static List<String> orders(List<RunDrop> route) {
        return route.stream().map(RunDrop::orderNumber).toList();
    }

    @Test
    void aRunThatFitsOneVanIsOneRouteOrderedByRoadDistanceFromTheHub() {
        List<List<RunDrop>> routes = planner.plan(List.of(drop("C", 1, 30_000.0), drop("A", 2, 12_000.0),
                drop("B", 1, 21_500.0)), 40);
        assertThat(routes).hasSize(1);
        assertThat(orders(routes.getFirst())).containsExactly("A", "B", "C");
    }

    @Test
    void dropsThatCouldNotBeMeasuredComeLastByOrderNumber() {
        List<List<RunDrop>> routes = planner.plan(List.of(drop("B", 1, null), drop("D", 1, 5_000.0),
                drop("A", 1, null), drop("E", 1, 9_000.0)), 40);
        assertThat(orders(routes.getFirst())).containsExactly("D", "E", "A", "B");
    }

    @Test
    void aRunTooBigForOneVanIsSplitIntoBalancedRoutesNearestFirst() {
        List<RunDrop> drops = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            drops.add(drop(String.format("O%02d", i), 1, i * 1000.0));
        }
        List<List<RunDrop>> routes = planner.plan(drops, 40);
        assertThat(routes).hasSize(2);
        assertThat(routes.get(0)).hasSize(25);
        assertThat(routes.get(1)).hasSize(25);
        assertThat(orders(routes.get(0)).getFirst()).isEqualTo("O01");
        assertThat(orders(routes.get(1)).getFirst()).isEqualTo("O26");
        assertThat(routes.stream().mapToInt(List::size).sum()).isEqualTo(50);
    }

    @Test
    void anOrderIsNeverSplitAndNoRouteExceedsTheVanWhenOrdersFit() {
        List<RunDrop> drops = List.of(drop("A", 3, 1_000.0), drop("B", 3, 2_000.0), drop("C", 3, 3_000.0),
                drop("D", 3, 4_000.0), drop("E", 1, 5_000.0));
        List<List<RunDrop>> routes = planner.plan(drops, 6);
        assertThat(routes).hasSize(3);
        for (List<RunDrop> r : routes) {
            assertThat(r.stream().mapToInt(RunDrop::packages).sum()).isLessThanOrEqualTo(6);
        }
        assertThat(routes.stream().flatMap(List::stream).map(RunDrop::orderNumber)).containsExactly("A", "B", "C",
                "D", "E");
    }

    @Test
    void anOrderBiggerThanTheVanGetsARouteOfItsOwn() {
        List<List<RunDrop>> routes = planner.plan(List.of(drop("A", 9, 1_000.0), drop("B", 1, 2_000.0)), 4);
        assertThat(routes).hasSize(2);
        assertThat(orders(routes.get(0))).containsExactly("A");
        assertThat(orders(routes.get(1))).containsExactly("B");
    }

    @Test
    void noDropsNoRoutesAndACapacityBelowOneIsRefused() {
        assertThat(planner.plan(List.of(), 10)).isEmpty();
        assertThatThrownBy(() -> planner.plan(List.of(drop("A", 1, 1.0)), 0))
                .isInstanceOf(FulfilmentRuleViolation.class);
    }
}
