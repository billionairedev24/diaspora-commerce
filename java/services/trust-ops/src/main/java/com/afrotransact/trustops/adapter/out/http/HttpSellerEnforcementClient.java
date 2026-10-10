package com.afrotransact.trustops.adapter.out.http;

import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.domain.SuspensionReason;
import com.afrotransact.trustops.port.SellerEnforcementClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** seller-service {@code POST /v1/orgs/{orgId}/suspend} and {@code /reinstate} (contracts/openapi/seller.yaml). */
@Component
class HttpSellerEnforcementClient implements SellerEnforcementClient {

    private static final String SERVICE = "seller";

    private final Downstream downstream;
    private final RestClient client;

    HttpSellerEnforcementClient(Downstream downstream, TrustOpsProperties props) {
        this.downstream = downstream;
        this.client = downstream.client(props.seller().baseUrl(), null);
    }

    @Override
    public void suspend(String orgId, SuspensionReason reason, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", reason.name());
        body.put("note", trim(note));
        post(orgId, "suspend", body);
    }

    @Override
    public void reinstate(String orgId, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("note", trim(note));
        post(orgId, "reinstate", body);
    }

    private void post(String orgId, String action, Map<String, Object> body) {
        downstream.call(SERVICE, client, c -> c.post().uri("/v1/orgs/{orgId}/{action}", orgId, action)
                .headers(h -> downstream.headers(h, null)).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class), n -> n);
    }

    private static String trim(String note) {
        return note == null ? null : note.length() > 1000 ? note.substring(0, 1000) : note;
    }
}
