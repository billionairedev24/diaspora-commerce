package com.afrotransact.taxreturns.adapter.out.persistence;

import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for value objects stored as documents. */
@Component
class JsonCodec {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final JsonMapper mapper;

    JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    List<String> strings(String json) {
        return json == null ? List.of() : mapper.readValue(json, STRINGS);
    }

    Map<String, String> readStringMap(String json) {
        return json == null ? Map.of() : mapper.readValue(json, STRING_MAP);
    }

    /** A JSON object as a map (numbers as Integer, Long or BigDecimal, booleans, text); null reads as an empty map. */
    Map<String, Object> readMap(String json) {
        return json == null ? Map.of() : mapper.readValue(json, OBJECT_MAP);
    }

    /** A JSON value as plain maps, lists and scalars; null reads as null. */
    Object readTree(String json) {
        return json == null ? null : mapper.readValue(json, Object.class);
    }

    <T> T read(String json, Class<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    ReturnAddress address(String json) {
        return json == null ? null : mapper.readValue(json, ReturnAddress.class);
    }
}
