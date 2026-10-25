package com.afrotransact.fulfilment.adapter.out.persistence;

import java.util.List;

import com.afrotransact.fulfilment.domain.ContactAttempt;
import com.afrotransact.fulfilment.domain.RunPickupPoint;
import com.afrotransact.fulfilment.domain.TaskLine;
import com.afrotransact.fulfilment.port.CourierEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for value objects stored as documents. */
@Component
class Json {

    private static final TypeReference<List<ContactAttempt>> CONTACTS = new TypeReference<>() { };
    private static final TypeReference<List<TaskLine>> LINES = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final JsonMapper mapper;

    Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    List<ContactAttempt> contacts(String json) {
        return json == null ? List.of() : mapper.readValue(json, CONTACTS);
    }

    List<TaskLine> lines(String json) {
        return json == null ? List.of() : mapper.readValue(json, LINES);
    }

    List<String> strings(String json) {
        return json == null ? List.of() : mapper.readValue(json, STRINGS);
    }

    RunPickupPoint pickupPoint(String json) {
        return json == null ? null : mapper.readValue(json, RunPickupPoint.class);
    }

    CourierEvent courier(String json) {
        return json == null ? null : mapper.readValue(json, CourierEvent.class);
    }
}
