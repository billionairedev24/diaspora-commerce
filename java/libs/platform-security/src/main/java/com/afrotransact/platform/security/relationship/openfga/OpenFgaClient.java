package com.afrotransact.platform.security.relationship.openfga;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.platform.security.relationship.RelationshipCheckUnavailableException;
import com.afrotransact.platform.security.relationship.RelationshipTuple;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin client for the OpenFGA HTTP API (check, write, list stores) on {@link RestClient}; no SDK, so no extra
 * dependencies to converge. 4xx answers (unknown relation, bad tuple) are programming errors and throw
 * {@link IllegalStateException}; timeouts and 5xx throw {@link RelationshipCheckUnavailableException}.
 */
class OpenFgaClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final OpenFgaProperties props;
    private volatile String storeId;

    OpenFgaClient(RestClient http, OpenFgaProperties props) {
        this.http = http;
        this.props = props;
        this.storeId = props.storeId();
    }

    boolean check(RelationshipTuple tuple, List<RelationshipTuple> contextualTuples) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tuple_key", key(tuple));
        if (!contextualTuples.isEmpty()) {
            body.put("contextual_tuples", Map.of("tuple_keys", keys(contextualTuples)));
        }
        modelId(body);
        Map<String, Object> response = post("/stores/" + storeId() + "/check", body);
        return Boolean.TRUE.equals(response.get("allowed"));
    }

    void write(List<RelationshipTuple> writes, List<RelationshipTuple> deletes) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!writes.isEmpty()) {
            body.put("writes", Map.of("tuple_keys", keys(writes), "on_duplicate", "ignore"));
        }
        if (!deletes.isEmpty()) {
            body.put("deletes", Map.of("tuple_keys", keys(deletes), "on_missing", "ignore"));
        }
        modelId(body);
        post("/stores/" + storeId() + "/write", body);
    }

    /** The configured store id, else the id of the store named {@code storeName} (looked up once). */
    String storeId() {
        String id = storeId;
        if (id != null) {
            return id;
        }
        synchronized (this) {
            if (storeId == null) {
                storeId = lookUpStore();
            }
            return storeId;
        }
    }

    private String lookUpStore() {
        Map<String, Object> response = call(() -> http.get()
                .uri(u -> u.path("/stores").queryParam("name", props.storeName()).build())
                .retrieve().body(JSON));
        if (response.get("stores") instanceof List<?> stores) {
            for (Object s : stores) {
                if (s instanceof Map<?, ?> store && props.storeName().equals(store.get("name"))) {
                    return String.valueOf(store.get("id"));
                }
            }
        }
        throw new RelationshipCheckUnavailableException("OpenFGA store '" + props.storeName() + "' does not exist",
                null);
    }

    private void modelId(Map<String, Object> body) {
        if (props.modelId() != null) {
            body.put("authorization_model_id", props.modelId());
        }
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        return call(() -> http.post().uri(path).body(body).retrieve().body(JSON));
    }

    private static Map<String, Object> call(Call call) {
        try {
            Map<String, Object> response = call.run();
            return response == null ? Map.of() : response;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new IllegalStateException("OpenFGA rejected the request (" + e.getStatusCode().value() + "): "
                        + e.getResponseBodyAsString(), e);
            }
            throw new RelationshipCheckUnavailableException("OpenFGA answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new RelationshipCheckUnavailableException("OpenFGA is unreachable", e);
        }
    }

    private static List<Map<String, String>> keys(List<RelationshipTuple> tuples) {
        List<Map<String, String>> out = new ArrayList<>(tuples.size());
        tuples.forEach(t -> out.add(key(t)));
        return out;
    }

    private static Map<String, String> key(RelationshipTuple t) {
        return Map.of("user", t.user(), "relation", t.relation(), "object", t.object());
    }

    @FunctionalInterface
    private interface Call {
        Map<String, Object> run();
    }
}
