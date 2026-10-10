package com.afrotransact.ai.domain;

/** Tokens reported by the provider for one call (zero when the provider did not report usage). */
public record TokenUsage(long promptTokens, long completionTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0);

    public TokenUsage {
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts cannot be negative");
        }
    }

    public long total() {
        return promptTokens + completionTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(promptTokens + other.promptTokens, completionTokens + other.completionTokens);
    }
}
