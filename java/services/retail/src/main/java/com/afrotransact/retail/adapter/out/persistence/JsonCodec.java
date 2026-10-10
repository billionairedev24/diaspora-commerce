package com.afrotransact.retail.adapter.out.persistence;

import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for child collections stored with their aggregate. */
@Component
class JsonCodec {

    private final JsonMapper mapper;

    JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    <T> T read(String json, Class<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    <T> List<T> list(String json, TypeReference<List<T>> type) {
        return json == null ? List.of() : mapper.readValue(json, type);
    }
}
