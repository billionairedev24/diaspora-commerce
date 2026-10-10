package com.afrotransact.retail.adapter.out.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** S226: receipt dates follow the warehouse's local calendar (inventory node time zones, cached, with fallbacks). */
class InventoryWarehouseCalendarTest {

    /** 03:00 UTC on 10 October is still 9 October in Houston. */
    private static final Instant LATE_EVENING_IN_HOUSTON = Instant.parse("2026-10-10T03:00:00Z");

    private final WireMockServer inventory = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeEach
    void start() {
        inventory.start();
    }

    @AfterEach
    void stop() {
        inventory.stop();
    }

    private InventoryWarehouseCalendar calendar(Clock clock) {
        return new InventoryWarehouseCalendar(RestClient.builder().baseUrl(inventory.baseUrl()).build(),
                () -> Optional.of("svc-token"), JsonMapper.builder().build(), Map.of("LAX-FC", "America/Los_Angeles"),
                "UTC", Duration.ofHours(1), clock);
    }

    @Test
    void usesTheNodeTimeZoneFromInventoryAndCachesIt() {
        inventory.stubFor(WireMock.get("/v1/nodes").willReturn(WireMock.okJson("""
                [{"id":"HOU-FC","name":"Houston FC","kind":"FULFILMENT_CENTRE","country":"US",
                  "timezone":"America/Chicago","active":true},
                 {"id":"TEM-HUB","timezone":"Africa/Accra"}]""")));
        InventoryWarehouseCalendar c = calendar(Clock.fixed(LATE_EVENING_IN_HOUSTON, ZoneOffset.UTC));

        assertThat(c.today("HOU-FC")).isEqualTo(LocalDate.parse("2026-10-09"));
        assertThat(c.today("hou-fc")).isEqualTo(LocalDate.parse("2026-10-09"));
        assertThat(c.today("TEM-HUB")).isEqualTo(LocalDate.parse("2026-10-10"));
        assertThat(c.zoneOf("LAX-FC")).as("configured zone for a node inventory does not list")
                .isEqualTo(ZoneId.of("America/Los_Angeles"));
        assertThat(c.zoneOf("NOWHERE")).isEqualTo(ZoneId.of("UTC"));
        inventory.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/v1/nodes"))
                .withHeader("Authorization", WireMock.equalTo("Bearer svc-token")));
    }

    @Test
    void fallsBackToConfiguredZonesWhenInventoryIsDown() {
        inventory.stubFor(WireMock.get("/v1/nodes").willReturn(WireMock.serverError()));
        InventoryWarehouseCalendar c = calendar(Clock.fixed(LATE_EVENING_IN_HOUSTON, ZoneOffset.UTC));

        assertThat(c.today("LAX-FC")).isEqualTo(LocalDate.parse("2026-10-09"));
        assertThat(c.today("HOU-FC")).as("unknown and unconfigured: default zone").isEqualTo(
                LocalDate.parse("2026-10-10"));
    }
}
