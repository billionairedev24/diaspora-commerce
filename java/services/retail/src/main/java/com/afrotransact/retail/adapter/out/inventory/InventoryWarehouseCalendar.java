package com.afrotransact.retail.adapter.out.inventory;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.retail.port.ServiceTokenProvider;
import com.afrotransact.retail.port.WarehouseCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Warehouse time zones from inventory-service {@code GET /v1/nodes} (retail-service token, {@code inventory:read}),
 * cached for {@code ttl}. When inventory cannot be reached the last known zones are kept; nodes never seen use the
 * configured zones ({@code at.retail.receiving.warehouse-zones}), then the default zone.
 */
public class InventoryWarehouseCalendar implements WarehouseCalendar {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryWarehouseCalendar.class);

    private final RestClient api;
    private final ServiceTokenProvider tokens;
    private final JsonMapper json;
    private final Map<String, ZoneId> configured;
    private final ZoneId defaultZone;
    private final Duration ttl;
    private final Clock clock;

    private Map<String, ZoneId> known = Map.of();
    private Instant refreshAt = Instant.EPOCH;

    public InventoryWarehouseCalendar(RestClient api, ServiceTokenProvider tokens, JsonMapper json,
                                      Map<String, String> configured, String defaultZone, Duration ttl, Clock clock) {
        this.api = api;
        this.tokens = tokens;
        this.json = json;
        Map<String, ZoneId> zones = new HashMap<>();
        (configured == null ? Map.<String, String>of() : configured).forEach((node, zone) ->
                zones.put(node.toUpperCase(Locale.ROOT), ZoneId.of(zone)));
        this.configured = Map.copyOf(zones);
        this.defaultZone = ZoneId.of(defaultZone == null || defaultZone.isBlank() ? "UTC" : defaultZone);
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public ZoneId zoneOf(String nodeId) {
        String id = nodeId == null ? "" : nodeId.trim().toUpperCase(Locale.ROOT);
        ZoneId zone = current().get(id);
        if (zone != null) {
            return zone;
        }
        return configured.getOrDefault(id, defaultZone);
    }

    @Override
    public LocalDate today(String nodeId) {
        return LocalDate.now(clock.withZone(zoneOf(nodeId)));
    }

    private synchronized Map<String, ZoneId> current() {
        Instant now = Instant.now(clock);
        if (now.isBefore(refreshAt)) {
            return known;
        }
        try {
            String body = api.get().uri("/v1/nodes").headers(h -> tokens.token().ifPresent(h::setBearerAuth))
                    .retrieve().body(String.class);
            Map<String, ZoneId> zones = new HashMap<>();
            for (JsonNode node : json.readTree(body == null ? "[]" : body)) {
                String id = node.path("id").asString(null);
                String tz = node.path("timezone").asString(null);
                if (id != null && tz != null) {
                    zones.put(id.toUpperCase(Locale.ROOT), ZoneId.of(tz));
                }
            }
            known = Map.copyOf(zones);
            refreshAt = now.plus(ttl);
        } catch (RestClientException | JacksonException | DateTimeException | IllegalStateException e) {
            LOG.warn("Warehouse time zones from inventory unavailable ({}); using {} known / configured zones",
                    e.getMessage(), known.isEmpty() ? "the" : "the last");
            refreshAt = now.plus(Duration.ofMinutes(1));
        }
        return known;
    }
}
