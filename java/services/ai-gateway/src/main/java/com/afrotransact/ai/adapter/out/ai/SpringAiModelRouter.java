package com.afrotransact.ai.adapter.out.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.afrotransact.ai.application.AiSettings;
import com.afrotransact.ai.domain.FailureKind;
import com.afrotransact.ai.domain.ModelRoute;
import com.afrotransact.ai.domain.ModelRoutingPolicy;
import com.afrotransact.ai.domain.TaskType;
import com.afrotransact.ai.domain.TokenUsage;
import com.afrotransact.ai.port.AiMetrics;
import com.afrotransact.ai.port.EmbeddingReply;
import com.afrotransact.ai.port.ModelReply;
import com.afrotransact.ai.port.ModelRequest;
import com.afrotransact.ai.port.ModelRouter;
import com.afrotransact.ai.port.ModelUnavailableException;
import com.afrotransact.ai.port.ToolCallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link ModelRouter} on Spring AI's OpenAI-compatible client pointed at OpenRouter. Our own fallback loop (rather than
 * OpenRouter's {@code models} array) keeps routing provider-agnostic: the same table works against any
 * OpenAI-compatible endpoint. Prompts are never logged.
 */
@Component
class SpringAiModelRouter implements ModelRouter {

    private static final Logger LOG = LoggerFactory.getLogger(SpringAiModelRouter.class);

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final ModelRoutingPolicy routing;
    private final AiMetrics metrics;
    private final int embeddingDimensions;
    private final Sleeper sleeper;
    /** OpenRouter request-body extension: {@code {"provider": {...}}}, empty when no preference is set. */
    private final Map<String, Object> extraBody;

    @Autowired
    SpringAiModelRouter(ChatModel chatModel, EmbeddingModel embeddingModel, ModelRoutingPolicy routing,
                        AiMetrics metrics, AiSettings settings) {
        this(chatModel, embeddingModel, routing, metrics, settings.embeddingDimensions(), settings.denyDataCollection(),
                settings.zeroDataRetention(), Sleeper.threadSleep());
    }

    SpringAiModelRouter(ChatModel chatModel, EmbeddingModel embeddingModel, ModelRoutingPolicy routing,
                        AiMetrics metrics, int embeddingDimensions, boolean denyDataCollection,
                        boolean zeroDataRetention, Sleeper sleeper) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.routing = routing;
        this.metrics = metrics;
        this.embeddingDimensions = embeddingDimensions;
        this.sleeper = sleeper;
        this.extraBody = providerPreferences(denyDataCollection, zeroDataRetention);
    }

    /**
     * OpenRouter provider routing preferences: {@code provider.data_collection = "deny"} limits routing to providers
     * that do not store or train on prompts, {@code provider.zdr = true} to zero-data-retention endpoints. Nothing is
     * sent when neither is wanted (OpenRouter's defaults apply).
     */
    static Map<String, Object> providerPreferences(boolean denyDataCollection, boolean zeroDataRetention) {
        Map<String, Object> provider = new LinkedHashMap<>();
        if (denyDataCollection) {
            provider.put("data_collection", "deny");
        }
        if (zeroDataRetention) {
            provider.put("zdr", Boolean.TRUE);
        }
        return provider.isEmpty() ? Map.of() : Map.of("provider", Map.copyOf(provider));
    }

    @Override
    public ModelReply chat(ModelRequest request) {
        return run(request.task(), request.degrade(), () -> true, (model, route) -> {
            ChatResponse response = chatModel.call(new Prompt(SpringAiMessages.toSpringAi(request.messages()),
                    options(model, route, request)));
            return new Served(new ModelReply(SpringAiMessages.text(response), SpringAiMessages.toolCalls(response),
                    SpringAiMessages.usage(response), model, 0, List.of()), null);
        }).reply();
    }

    @Override
    public ModelReply stream(ModelRequest request, Consumer<String> onText) {
        AtomicBoolean delivered = new AtomicBoolean(false);
        return run(request.task(), request.degrade(), () -> !delivered.get(), (model, route) -> {
            StringBuilder text = new StringBuilder();
            List<ToolCallRequest> calls = new ArrayList<>();
            AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.NONE);
            chatModel.stream(new Prompt(SpringAiMessages.toSpringAi(request.messages()),
                            options(model, route, request)))
                    .timeout(route.timeout())
                    .doOnNext(chunk -> {
                        String delta = SpringAiMessages.text(chunk);
                        if (!delta.isEmpty()) {
                            text.append(delta);
                            delivered.set(true);
                            onText.accept(delta);
                        }
                        calls.addAll(SpringAiMessages.toolCalls(chunk));
                        TokenUsage u = SpringAiMessages.usage(chunk);
                        if (u.total() > usage.get().total()) {
                            usage.set(u);
                        }
                    })
                    .blockLast(route.timeout().multipliedBy(4));
            return new Served(new ModelReply(text.toString(), calls, usage.get(), model, 0, List.of()), null);
        }).reply();
    }

    @Override
    public EmbeddingReply embed(TaskType task, List<String> inputs, boolean degrade) {
        Served served = run(task, degrade, () -> true, (model, route) -> {
            OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder();
            options.model(model);
            options.timeout(route.timeout());
            options.encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT);
            if (!extraBody.isEmpty()) {
                options.extraBody(extraBody);
            }
            if (embeddingDimensions > 0) {
                options.dimensions(embeddingDimensions);
            }
            EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(inputs, options.build()));
            if (response.getResults().isEmpty()) {
                throw new IllegalStateException("Embedding response without vectors");
            }
            TokenUsage usage = response.getMetadata() == null ? TokenUsage.NONE
                    : SpringAiMessages.usage(response.getMetadata().getUsage());
            return new Served(new ModelReply("", List.of(), usage, model, 0, List.of()),
                    response.getResults().getFirst().getOutput());
        });
        return new EmbeddingReply(served.vector(), served.reply().usage(), served.reply().model(),
                served.reply().attempts());
    }

    private Served run(TaskType task, boolean degrade, BooleanSupplier mayFallBack, Attempt attempt) {
        ModelRoute route = routing.route(task);
        List<String> candidates = routing.candidates(task, degrade);
        List<String> failed = new ArrayList<>();
        RuntimeException last = null;
        int attempts = 0;
        for (int i = 0; i < candidates.size(); i++) {
            String model = candidates.get(i);
            FailureKind kind = FailureKind.UNKNOWN;
            for (int n = 1; ; n++) {
                attempts++;
                long start = System.nanoTime();
                try {
                    Served served = attempt.call(model, route);
                    metrics.attempt(task.name(), model, "success", Duration.ofNanos(System.nanoTime() - start));
                    ModelReply r = served.reply();
                    return new Served(new ModelReply(r.text(), r.toolCalls(), r.usage(), r.model(), attempts, failed),
                            served.vector());
                } catch (RuntimeException e) {
                    kind = FailureClassifier.classify(e);
                    last = e;
                    metrics.attempt(task.name(), model, kind.name().toLowerCase(Locale.ROOT),
                            Duration.ofNanos(System.nanoTime() - start));
                    if (!mayFallBack.getAsBoolean()) {
                        throw new ModelUnavailableException("Model " + model + " failed mid-stream for " + task,
                                List.of(model), e);
                    }
                    if (routing.shouldRetry(task, kind, n)) {
                        LOG.info("Model {} for {} failed ({}); retry {}", model, task, kind, n);
                        sleeper.sleep(route.backoffFor(n));
                        continue;
                    }
                    break;
                }
            }
            failed.add(model);
            if (kind == FailureKind.PRIVACY_POLICY) {
                LOG.warn("Model {} for {} skipped: no provider endpoint satisfies the privacy preferences "
                        + "(data_collection/zdr)", model, task);
            }
            if (i + 1 < candidates.size()) {
                LOG.warn("Model {} for {} failed ({}); falling back to {}", model, task, kind, candidates.get(i + 1));
                metrics.fallback(task.name(), model, kind.name().toLowerCase(Locale.ROOT));
            }
        }
        throw new ModelUnavailableException("No model could serve " + task + " (tried " + failed + ")", failed, last);
    }

    private OpenAiChatOptions options(String model, ModelRoute route, ModelRequest request) {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder();
        b.model(model);
        b.timeout(route.timeout());
        if (!extraBody.isEmpty()) {
            b.extraBody(extraBody);
        }
        if (request.maxTokens() != null) {
            b.maxTokens(request.maxTokens());
        }
        if (request.temperature() != null) {
            b.temperature(request.temperature());
        }
        if (!request.tools().isEmpty()) {
            List<ToolCallback> callbacks = request.tools().stream()
                    .<ToolCallback>map(DefinitionOnlyToolCallback::new).toList();
            b.toolCallbacks(callbacks);
        }
        return b.build();
    }

    /** One attempt against one model. */
    @FunctionalInterface
    private interface Attempt {
        Served call(String model, ModelRoute route);
    }

    /** What a successful attempt produced (vector only for embeddings). */
    private record Served(ModelReply reply, float[] vector) {
    }

    /** Waits between retries (replaced in tests). */
    @FunctionalInterface
    interface Sleeper {

        void sleep(Duration duration);

        static Sleeper threadSleep() {
            return d -> {
                try {
                    Thread.sleep(d);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
        }
    }
}
