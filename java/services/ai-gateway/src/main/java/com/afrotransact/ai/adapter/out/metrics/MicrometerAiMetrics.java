package com.afrotransact.ai.adapter.out.metrics;

import java.time.Duration;

import com.afrotransact.ai.domain.TokenUsage;
import com.afrotransact.ai.port.AiMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Prometheus series (tags are model ids, task and feature names only):
 * {@code ai_model_latency_seconds{task,model,outcome}}, {@code ai_tokens_total{task,model,kind}},
 * {@code ai_cost_usd_total{task,model}}, {@code ai_fallbacks_total{task,from,reason}},
 * {@code ai_budget_decisions_total{feature,verdict}}, {@code ai_guardrail_total{kind}},
 * {@code ai_tool_calls_total{tool,outcome}}. Spring AI adds {@code gen_ai_client_*} observations.
 */
@Component
class MicrometerAiMetrics implements AiMetrics {

    private final MeterRegistry registry;

    MicrometerAiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void attempt(String task, String model, String outcome, Duration latency) {
        Timer.builder("ai.model.latency").description("Latency of one provider attempt")
                .tag("task", task).tag("model", model).tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry).record(latency);
    }

    @Override
    public void usage(String task, String model, TokenUsage usage, long costMicros) {
        Counter.builder("ai.tokens").description("Tokens reported by the provider")
                .tag("task", task).tag("model", model).tag("kind", "prompt")
                .register(registry).increment(usage.promptTokens());
        Counter.builder("ai.tokens").description("Tokens reported by the provider")
                .tag("task", task).tag("model", model).tag("kind", "completion")
                .register(registry).increment(usage.completionTokens());
        Counter.builder("ai.cost.usd").description("Estimated cost from the configured price table")
                .tag("task", task).tag("model", model)
                .register(registry).increment(costMicros / 1_000_000.0);
    }

    @Override
    public void fallback(String task, String fromModel, String reason) {
        Counter.builder("ai.fallbacks").description("Fallbacks to the next model of a route")
                .tag("task", task).tag("from", fromModel).tag("reason", reason)
                .register(registry).increment();
    }

    @Override
    public void budget(String feature, String verdict) {
        Counter.builder("ai.budget.decisions").description("Budget checks by verdict")
                .tag("feature", feature).tag("verdict", verdict)
                .register(registry).increment();
    }

    @Override
    public void guardrail(String kind, int count) {
        if (count > 0) {
            Counter.builder("ai.guardrail").description("Guardrail activity (redactions, removed injections, "
                            + "dropped ungrounded claims, topic refusals)")
                    .tag("kind", kind).register(registry).increment(count);
        }
    }

    @Override
    public void toolCall(String tool, String outcome) {
        Counter.builder("ai.tool.calls").description("Lola tool calls")
                .tag("tool", tool).tag("outcome", outcome)
                .register(registry).increment();
    }
}
