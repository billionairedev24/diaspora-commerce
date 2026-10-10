package com.afrotransact.inventory.adapter.out.carrier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.inventory.domain.PostalAddress;
import com.afrotransact.inventory.port.CarrierGateway;
import com.afrotransact.inventory.port.ServiceTokenProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * carrier-gateway over HTTP with the inventory-service token ({@code shipping:label}): {@code POST /v1/labels}
 * (Idempotency-Key, fulfilment FBA) and {@code POST /v1/tracking}. 5xx / 408 / 429 / I/O are retryable
 * ({@code carrier-gateway.carrier-unavailable}); other answers are rejections with the gateway's problem code.
 */
public class HttpCarrierGateway implements CarrierGateway {

    private final RestClient api;
    private final ServiceTokenProvider tokens;
    private final JsonMapper json;

    public HttpCarrierGateway(RestClient api, ServiceTokenProvider tokens, JsonMapper json) {
        this.api = api;
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    public PurchasedLabel buyLabel(LabelPurchase p, String idempotencyKey) {
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("reference", p.reference());
        shipment.put("originNode", p.originNode());
        shipment.put("from", address(p.from()));
        shipment.put("to", address(p.to()));
        List<Map<String, Object>> parcels = new ArrayList<>();
        for (Parcel parcel : p.parcels()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("weightLb", parcel.weightLb());
            putIf(m, "lengthIn", parcel.lengthIn());
            putIf(m, "widthIn", parcel.widthIn());
            putIf(m, "heightIn", parcel.heightIn());
            parcels.add(m);
        }
        shipment.put("parcels", parcels);
        if (p.shipDate() != null) {
            shipment.put("shipDate", p.shipDate().toString());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("carrier", p.carrier());
        body.put("service", p.service());
        body.put("shipment", shipment);
        body.put("fulfilment", "FBA");
        body.put("shipmentId", p.reference());
        String response = call(() -> api.post().uri("/v1/labels").headers(this::auth)
                .header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)).retrieve().body(String.class));
        JsonNode l = json.readTree(response == null ? "{}" : response);
        return new PurchasedLabel(l.path("id").asString(null), l.path("carrier").asString(p.carrier()),
                l.path("service").asString(p.service()), l.path("trackingNumber").asString(null),
                l.path("costMinor").asLong(0), l.path("currency").asString("USD"),
                l.path("documentUrl").asString(null));
    }

    @Override
    public void registerTracking(String carrier, String trackingNumber, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("carrier", carrier);
        body.put("trackingNumber", trackingNumber);
        body.put("reference", reference);
        call(() -> api.post().uri("/v1/tracking").headers(this::auth).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)).retrieve().body(String.class));
    }

    private static Map<String, Object> address(PostalAddress a) {
        Map<String, Object> m = new LinkedHashMap<>();
        putIf(m, "name", a.name());
        putIf(m, "phone", a.phone());
        putIf(m, "line1", a.line1());
        putIf(m, "line2", a.line2());
        putIf(m, "city", a.city());
        putIf(m, "state", a.region());
        putIf(m, "postalCode", a.postalCode());
        putIf(m, "country", a.country());
        return m;
    }

    private static void putIf(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private void auth(HttpHeaders headers) {
        tokens.token().ifPresent(headers::setBearerAuth);
    }

    private String call(Call call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            JsonNode problem = problem(e.getResponseBodyAsString());
            String code = problem.path("code").asString("carrier-gateway.http-" + status);
            boolean retryable = e.getStatusCode().is5xxServerError() || status == 408 || status == 429;
            throw new CarrierException(code, problem.path("detail").asString("carrier-gateway answered " + status),
                    retryable, e);
        } catch (RestClientException e) {
            throw new CarrierException("carrier-gateway.carrier-unavailable", "carrier-gateway unreachable: "
                    + e.getMessage(), true, e);
        }
    }

    private JsonNode problem(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RuntimeException e) {
            return json.readTree("{}");
        }
    }

    @FunctionalInterface
    private interface Call {
        String run();
    }
}
