package com.afrotransact.trustops.adapter.out.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.domain.AiFlag;
import com.afrotransact.trustops.domain.TriageHint;
import com.afrotransact.trustops.port.TriageClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** ai-gateway {@code POST /v1/ai/triage} (Report: riskLevel, summary, flags with cited fields, model). */
@Component
class HttpTriageClient implements TriageClient {

    private static final String SERVICE = "ai-gateway";

    private final Downstream downstream;
    private final RestClient client;
    private final boolean enabled;

    HttpTriageClient(Downstream downstream, TrustOpsProperties props) {
        this.downstream = downstream;
        this.client = downstream.client(props.ai().baseUrl(), props.ai().timeout());
        this.enabled = props.ai().enabled();
    }

    @Override
    public Optional<TriageHint> triage(String subjectType, String subjectId, Map<String, String> fields) {
        if (!enabled) {
            return Optional.empty();
        }
        Map<String, Object> body = Map.of("subjectType", subjectType, "subjectId", subjectId, "fields", fields);
        return Optional.of(downstream.call(SERVICE, client, c -> c.post().uri("/v1/ai/triage")
                .headers(h -> downstream.headers(h, null)).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class), HttpTriageClient::hint));
    }

    private static TriageHint hint(JsonNode n) {
        List<AiFlag> flags = new ArrayList<>();
        for (JsonNode f : n.path("flags")) {
            List<String> cited = new ArrayList<>();
            f.path("citedFields").forEach(c -> cited.add(c.asString()));
            flags.add(new AiFlag(f.path("code").asString(null), f.path("description").asString(null), cited));
        }
        return new TriageHint(n.path("riskLevel").asString(null), n.path("summary").asString(null), flags,
                n.path("model").asString(null));
    }
}
