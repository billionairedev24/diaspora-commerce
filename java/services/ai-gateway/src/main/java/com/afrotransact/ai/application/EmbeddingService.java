package com.afrotransact.ai.application;

/** Text embeddings for semantic search (called by the Go search service with {@code purpose=search-query}). */
public interface EmbeddingService {

    Result embed(Caller caller, String input, String purpose);

    /**
     * An embedding.
     *
     * @param embedding  the vector
     * @param model      model that produced it
     * @param dimensions vector length
     */
    record Result(float[] embedding, String model, int dimensions) {
    }
}
