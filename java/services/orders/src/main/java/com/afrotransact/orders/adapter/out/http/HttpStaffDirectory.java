package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.StaffDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Staff roster from identity-service ({@code GET /v1/internal/staff}, service token with {@code users:read}): who
 * holds a permission or role, and names for ids.
 */
@Component
class HttpStaffDirectory implements StaffDirectory {

    private final Upstream identity;
    private final ServiceTokenProvider tokens;

    HttpStaffDirectory(RestClient.Builder builder, @Value("${at.orders.upstreams.identity-url}") String baseUrl,
                       @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.identity = new Upstream("identity", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public List<StaffPerson> withPermission(String permission) {
        return fetch("permission", permission);
    }

    @Override
    public List<StaffPerson> withRole(String roleKey) {
        return fetch("role", roleKey);
    }

    @Override
    public List<StaffPerson> people(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return fetch("ids", String.join(",", userIds));
    }

    private List<StaffPerson> fetch(String param, String value) {
        JsonNode body = identity.call(c -> c.get()
                .uri(b -> b.path("/v1/internal/staff").queryParam(param, value).build())
                .headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(JsonNode.class));
        List<StaffPerson> out = new ArrayList<>();
        if (body != null && body.isArray()) {
            for (JsonNode n : body) {
                out.add(new StaffPerson(n.path("userId").asString(), n.path("name").asString("")));
            }
        }
        return List.copyOf(out);
    }
}
