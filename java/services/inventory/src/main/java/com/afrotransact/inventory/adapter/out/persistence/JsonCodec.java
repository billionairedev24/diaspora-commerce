package com.afrotransact.inventory.adapter.out.persistence;

import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for documents stored in columns. */
@Component
class JsonCodec {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final JsonMapper mapper;

    JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    <T> T read(String json, TypeReference<T> type) {
        return mapper.readValue(json, type);
    }

    List<String> strings(String json) {
        return json == null ? List.of() : mapper.readValue(json, STRINGS);
    }
}
