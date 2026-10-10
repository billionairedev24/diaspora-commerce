package com.afrotransact.platform.events;

/** Serialises event payloads to JSON. */
public interface EventSerializer {

    String toJson(Object payload);
}
