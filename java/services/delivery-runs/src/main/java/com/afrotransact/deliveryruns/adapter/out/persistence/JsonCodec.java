package com.afrotransact.deliveryruns.adapter.out.persistence;

import java.util.Map;

import com.afrotransact.deliveryruns.domain.PickupPoint;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for value objects stored as documents. */
@Component
class JsonCodec {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final JsonMapper mapper;

    JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    Map<String, String> stringMap(String json) {
        return json == null ? Map.of() : mapper.readValue(json, STRING_MAP);
    }

    PickupPoint pickupPoint(String json) {
        return json == null ? null : mapper.readValue(json, PickupPoint.class);
    }
}
