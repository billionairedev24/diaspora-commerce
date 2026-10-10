package com.afrotransact.ai.port;

import java.util.Arrays;

import com.afrotransact.ai.domain.TokenUsage;

/**
 * Embedding vector of one input.
 *
 * @param vector     the embedding
 * @param usage      provider-reported usage
 * @param model      model id that answered
 * @param attempts   total attempts across retries and fallbacks
 */
public record EmbeddingReply(float[] vector, TokenUsage usage, String model, int attempts) {

    public EmbeddingReply {
        vector = vector == null ? new float[0] : vector.clone();
        usage = usage == null ? TokenUsage.NONE : usage;
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EmbeddingReply other && Arrays.equals(vector, other.vector)
                && usage.equals(other.usage) && model.equals(other.model) && attempts == other.attempts;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(vector) * 31 + model.hashCode();
    }

    @Override
    public String toString() {
        return "EmbeddingReply[dimensions=" + vector.length + ", model=" + model + "]";
    }
}
