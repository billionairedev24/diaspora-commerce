package com.afrotransact.fulfilment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ZoneRoutePlannerTest {

    private final RoutePlanner planner = new ZoneRoutePlanner();

    private static RoutableShipment s(String id, String order, String zip, String zone) {
        return new RoutableShipment(id, order, PackageOrigin.FC, "org", zip, zone);
    }

    @Test
    void groupsByZoneKeepsOrdersTogetherAndCapsDropoffs() {
        List<PlannedRoute> plans = planner.plan(List.of(
                s("s1", "O1", "77042", "Houston SW"), s("s2", "O2", "77057", "Houston SW"),
                s("s3", "O1", "77042", "Houston SW"), s("s4", "O3", "77081", "Houston SW"),
                s("s5", "O4", "77002", "Downtown"), s("s6", "O5", "77074", null)), 2);
        assertThat(plans).extracting(PlannedRoute::zone).containsExactly("Downtown", "Houston SW", "Houston SW",
                "UNZONED");
        assertThat(plans.get(1).shipments()).extracting(RoutableShipment::shipmentId)
                .containsExactly("s1", "s3", "s2");
        assertThat(plans.get(2).shipments()).extracting(RoutableShipment::shipmentId).containsExactly("s4");
    }
}
