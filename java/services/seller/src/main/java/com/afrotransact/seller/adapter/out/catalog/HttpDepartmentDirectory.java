package com.afrotransact.seller.adapter.out.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.afrotransact.seller.domain.Department;
import com.afrotransact.seller.domain.Department.Requirement;
import com.afrotransact.seller.port.DepartmentDirectory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * The catalog's departments from catalog-service's public tree ({@code GET /v1/categories}): active nodes of level
 * DEPARTMENT with their code, name and compliance data. Cached for a short while; when the catalog cannot be reached
 * the last answer is served, and with none cached the call fails ({@link DirectoryUnavailable}).
 */
public class HttpDepartmentDirectory implements DepartmentDirectory {

    private final RestClient catalog;
    private final Duration ttl;
    private final Clock clock;
    private volatile List<Department> cached;
    private volatile Instant freshUntil = Instant.EPOCH;

    public HttpDepartmentDirectory(String baseUrl, Duration timeout, Duration ttl, Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.catalog = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public List<Department> departments() {
        Instant now = Instant.now(clock);
        List<Department> current = cached;
        if (current != null && now.isBefore(freshUntil)) {
            return current;
        }
        try {
            JsonNode tree = catalog.get().uri("/v1/categories").retrieve().body(JsonNode.class);
            List<Department> out = new ArrayList<>();
            if (tree != null && tree.isArray()) {
                for (JsonNode n : tree) {
                    if ("DEPARTMENT".equals(n.path("level").asString("")) && !n.path("code").asString("").isBlank()) {
                        out.add(new Department(n.path("code").asString(), n.path("name").asString(), requirements(n)));
                    }
                }
            }
            cached = List.copyOf(out);
            freshUntil = now.plus(ttl);
            return cached;
        } catch (RestClientException ex) {
            if (current != null) {
                return current;
            }
            throw new DirectoryUnavailable("catalog-service departments unavailable: " + ex.getMessage(), ex);
        }
    }

    private static Set<Requirement> requirements(JsonNode node) {
        Set<Requirement> out = EnumSet.noneOf(Requirement.class);
        for (JsonNode c : node.path("compliance")) {
            try {
                out.add(Requirement.valueOf(c.asString("")));
            } catch (IllegalArgumentException ignored) {
                // a requirement this service does not know yet asks for nothing here
            }
        }
        return out;
    }
}
