package com.afrotransact.ai.port;

import java.util.List;

/** Every model of the route failed (HTTP 503, code {@code ai.model-unavailable}). */
public class ModelUnavailableException extends RuntimeException {

    public static final String CODE = "ai.model-unavailable";
    private static final long serialVersionUID = 1L;

    private final transient List<String> triedModels;

    public ModelUnavailableException(String message, List<String> triedModels, Throwable lastFailure) {
        super(message, lastFailure);
        this.triedModels = List.copyOf(triedModels);
    }

    public List<String> triedModels() {
        return triedModels;
    }
}
