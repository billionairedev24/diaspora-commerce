package com.afrotransact.platform.events;

import tools.jackson.databind.json.JsonMapper;

/** Jackson 3 implementation of {@link EventSerializer}. */
public class JacksonEventSerializer implements EventSerializer {

    private final JsonMapper mapper;

    public JacksonEventSerializer(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String toJson(Object payload) {
        return mapper.writeValueAsString(payload);
    }
}
