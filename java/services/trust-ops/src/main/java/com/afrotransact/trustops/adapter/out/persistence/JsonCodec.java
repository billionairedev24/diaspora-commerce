package com.afrotransact.trustops.adapter.out.persistence;

import java.util.List;

import com.afrotransact.trustops.domain.Evidence;
import com.afrotransact.trustops.domain.TriageHint;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for value objects stored as documents. */
@Component
class JsonCodec {

    private static final TypeReference<List<Evidence>> EVIDENCE = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final JsonMapper mapper;

    JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    TriageHint triage(String json) {
        return json == null ? null : mapper.readValue(json, TriageHint.class);
    }

    List<Evidence> evidence(String json) {
        return json == null ? List.of() : mapper.readValue(json, EVIDENCE);
    }

    List<String> strings(String json) {
        return json == null ? List.of() : mapper.readValue(json, STRINGS);
    }
}
