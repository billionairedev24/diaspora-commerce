package com.afrotransact.bookings.adapter.out.persistence;

import java.util.List;
import java.util.Set;

import com.afrotransact.bookings.domain.AddOn;
import com.afrotransact.bookings.domain.ServiceMode;
import com.afrotransact.bookings.domain.TimeRange;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for value objects stored as documents. */
@Component
class JsonCodec {

    private static final TypeReference<List<TimeRange>> RANGES = new TypeReference<>() { };
    private static final TypeReference<Set<ServiceMode>> MODES = new TypeReference<>() { };
    private static final TypeReference<List<AddOn>> ADD_ONS = new TypeReference<>() { };

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

    List<TimeRange> ranges(String json) {
        return json == null ? List.of() : mapper.readValue(json, RANGES);
    }

    Set<ServiceMode> modes(String json) {
        return mapper.readValue(json, MODES);
    }

    List<AddOn> addOns(String json) {
        return json == null ? List.of() : mapper.readValue(json, ADD_ONS);
    }
}
