package com.afrotransact.ai.port;

import java.time.Duration;

import com.afrotransact.ai.domain.TokenUsage;

/** Prometheus metrics per model and task. Tags never contain prompts, user ids or other personal data. */
public interface AiMetrics {

    /** One provider attempt: outcome is {@code success}, {@code rate_limited}, {@code server_error} … */
    void attempt(String task, String model, String outcome, Duration latency);

    void usage(String task, String model, TokenUsage usage, long costMicros);

    void fallback(String task, String fromModel, String reason);

    void budget(String feature, String verdict);

    /** Guardrail activity: {@code pii_redacted}, {@code injection_removed}, {@code grounding_dropped} … */
    void guardrail(String kind, int count);

    void toolCall(String tool, String outcome);
}
