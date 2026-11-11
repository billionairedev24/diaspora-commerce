package com.afrotransact.seller.adapter.out.identity;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.seller.adapter.out.usage.ServiceTokens;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.StaffDirectory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Staff roster from identity-service ({@code GET /v1/internal/staff}, service token with {@code users:read}): who
 * holds a permission or role, and names for ids.
 */
public class HttpStaffDirectory implements StaffDirectory {

    private final RestClient client;
    private final ServiceTokens tokens;

    public HttpStaffDirectory(String baseUrl, Duration timeout, ServiceTokens tokens) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).baseUrl(baseUrl).build();
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
        try {
            JsonNode body = client.get().uri(b -> b.path("/v1/internal/staff").queryParam(param, value).build())
                    .headers(h -> {
                        if (tokens != null && tokens.enabled()) {
                            h.setBearerAuth(tokens.token());
                        }
                    })
                    .retrieve().body(JsonNode.class);
            List<StaffPerson> out = new ArrayList<>();
            if (body != null && body.isArray()) {
                for (JsonNode n : body) {
                    out.add(new StaffPerson(n.path("userId").asString(), n.path("name").asString("")));
                }
            }
            return List.copyOf(out);
        } catch (RestClientException | IllegalStateException e) {
            throw new GatewayException("seller.staff-directory-unavailable",
                    "The staff directory is unavailable: " + e.getMessage(), false, e);
        }
    }
}
