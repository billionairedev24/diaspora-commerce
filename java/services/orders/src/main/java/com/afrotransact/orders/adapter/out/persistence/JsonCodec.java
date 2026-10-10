package com.afrotransact.orders.adapter.out.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for documents stored in columns. */
@Component
class JsonCodec {

    private final JsonMapper mapper;

    JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    <T> T read(String json, Class<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }
}
