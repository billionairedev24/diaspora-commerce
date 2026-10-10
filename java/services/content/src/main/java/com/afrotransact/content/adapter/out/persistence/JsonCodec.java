package com.afrotransact.content.adapter.out.persistence;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for documents stored in columns. */
@Component
class JsonCodec {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, Boolean>> BOOLEAN_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

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

    List<String> strings(String json) {
        return json == null ? List.of() : mapper.readValue(json, STRINGS);
    }

    Map<String, String> stringMap(String json) {
        return json == null ? Map.of() : mapper.readValue(json, STRING_MAP);
    }

    Map<String, Boolean> booleanMap(String json) {
        return json == null ? Map.of() : mapper.readValue(json, BOOLEAN_MAP);
    }

    Map<String, Object> objectMap(String json) {
        return json == null ? Map.of() : mapper.readValue(json, OBJECT_MAP);
    }
}
