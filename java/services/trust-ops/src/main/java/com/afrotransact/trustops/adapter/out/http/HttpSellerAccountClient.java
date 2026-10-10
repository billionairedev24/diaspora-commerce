package com.afrotransact.trustops.adapter.out.http;

import java.util.Map;
import java.util.Optional;

import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.port.SellerAccountClient;
import com.afrotransact.trustops.port.UpstreamException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * seller-service has no suspend endpoint (seller.yaml), so payouts are held with the Stripe-compliance hold:
 * {@code GET /v1/compliance/accounts} to find the organisation's connected account, then
 * {@code POST /v1/compliance/accounts/{accountId}/payouts/pause|resume}. Pausing an already paused account (409) is
 * treated as done.
 */
@Component
class HttpSellerAccountClient implements SellerAccountClient {

    private static final String SERVICE = "seller";

    private final Downstream downstream;
    private final RestClient client;

    HttpSellerAccountClient(Downstream downstream, TrustOpsProperties props) {
        this.downstream = downstream;
        this.client = downstream.client(props.seller().baseUrl(), null);
    }

    @Override
    public boolean holdPayouts(String orgId, String note) {
        return payouts(orgId, "pause", note);
    }

    @Override
    public boolean releasePayouts(String orgId, String note) {
        return payouts(orgId, "resume", note);
    }

    private boolean payouts(String orgId, String action, String note) {
        Optional<String> account = accountOf(orgId);
        if (account.isEmpty()) {
            return false;
        }
        String text = note == null ? "" : note.length() > 1000 ? note.substring(0, 1000) : note;
        try {
            downstream.call(SERVICE, client, c -> c.post()
                    .uri("/v1/compliance/accounts/{accountId}/payouts/{action}", account.get(), action)
                    .headers(h -> downstream.headers(h, null)).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("note", text)).retrieve().body(JsonNode.class), n -> n);
        } catch (UpstreamException e) {
            if (e.status() != 409) {
                throw e;
            }
        }
        return true;
    }

    private Optional<String> accountOf(String orgId) {
        JsonNode accounts = downstream.call(SERVICE, client, c -> c.get().uri("/v1/compliance/accounts")
                .headers(h -> downstream.headers(h, null)).retrieve().body(JsonNode.class), n -> n);
        for (JsonNode a : accounts) {
            if (orgId.equals(a.path("orgId").asString(null))) {
                return Optional.ofNullable(a.path("accountId").asString(null));
            }
        }
        return Optional.empty();
    }
}
