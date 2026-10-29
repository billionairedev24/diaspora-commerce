package com.afrotransact.ai.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.afrotransact.ai.domain.DefaultModelRoutingPolicy;
import com.afrotransact.ai.domain.ModelRoute;
import com.afrotransact.ai.domain.PriceTable;
import com.afrotransact.ai.domain.TaskType;
import com.afrotransact.ai.domain.TokenUsage;
import com.afrotransact.ai.port.AiMetrics;
import com.afrotransact.ai.port.ChatMessage;
import com.afrotransact.ai.port.ModelReply;
import com.afrotransact.ai.port.ModelRequest;
import com.afrotransact.ai.port.ModelUnavailableException;
import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.models.ErrorObject;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

class SpringAiModelRouterTest {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<String> fallbacks = new ArrayList<>();
    private final List<Map<String, Object>> sentExtraBodies = new CopyOnWriteArrayList<>();

    @Test
    void retriesServerErrorsThenFallsBackInConfiguredOrder() {
        SpringAiModelRouter router = router(model -> {
            if (!model.equals("third")) {
                throw InternalServerException.builder().statusCode(503).headers(Headers.builder().build()).build();
            }
            return "hello from third";
        }, 1);
        ModelReply reply = router.chat(request(false));
        assertThat(reply.text()).isEqualTo("hello from third");
        assertThat(reply.model()).isEqualTo("third");
        assertThat(calls).containsExactly("first", "first", "second", "second", "third");
        assertThat(reply.attempts()).isEqualTo(5);
        assertThat(reply.failedModels()).containsExactly("first", "second");
        assertThat(sleeps).containsExactly(Duration.ofMillis(10), Duration.ofMillis(10));
        assertThat(fallbacks).containsExactly("first:server_error", "second:server_error");
    }

    @Test
    void nonRetryableErrorsFallBackImmediately() {
        SpringAiModelRouter router = router(model -> {
            if (model.equals("first")) {
                throw new IllegalStateException("connection reset");
            }
            return "ok";
        }, 2);
        assertThat(router.chat(request(false)).model()).isEqualTo("second");
        assertThat(calls).containsExactly("first", "second");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void degradeUsesOnlyTheCheapestModel() {
        SpringAiModelRouter router = router(model -> "cheap answer", 1);
        assertThat(router.chat(request(true)).model()).isEqualTo("third");
        assertThat(calls).containsExactly("third");
    }

    @Test
    void allModelsFailingIsModelUnavailable() {
        SpringAiModelRouter router = router(model -> {
            throw new IllegalStateException("down");
        }, 0);
        assertThatThrownBy(() -> router.chat(request(false)))
                .isInstanceOf(ModelUnavailableException.class)
                .satisfies(e -> assertThat(((ModelUnavailableException) e).triedModels())
                        .containsExactly("first", "second", "third"));
    }

    @Test
    void chatAndStreamSendDataCollectionDenyAndZdrWhenEnabled() {
        SpringAiModelRouter router = router(model -> "ok", 0, true, true);
        router.chat(request(false));
        router.stream(request(false), text -> { });
        assertThat(sentExtraBodies).hasSize(2);
        for (Map<String, Object> body : sentExtraBodies) {
            assertThat(body).containsOnlyKeys("provider");
            assertThat(body.get("provider")).isEqualTo(Map.of("data_collection", "deny", "zdr", true));
        }
    }

    @Test
    void zdrIsOmittedWhenDisabled() {
        router(model -> "ok", 0, true, false).chat(request(false));
        assertThat(sentExtraBodies.getFirst().get("provider")).isEqualTo(Map.of("data_collection", "deny"));
    }

    @Test
    void nothingIsSentWhenDataCollectionIsAllowedAndZdrOff() {
        router(model -> "ok", 0, false, false).chat(request(false));
        assertThat(sentExtraBodies.getFirst()).isNullOrEmpty();
    }

    @Test
    void zdrAloneDoesNotSendDataCollection() {
        router(model -> "ok", 0, false, true).chat(request(false));
        assertThat(sentExtraBodies.getFirst().get("provider")).isEqualTo(Map.of("zdr", true));
    }

    @Test
    void embeddingsCarryTheSameProviderPreferences() {
        List<Map<String, Object>> seen = new ArrayList<>();
        EmbeddingModel embeddings = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                seen.add(((OpenAiEmbeddingOptions) request.getOptions()).getExtraBody());
                return new EmbeddingResponse(List.of(new Embedding(new float[] {1f, 2f}, 0)));
            }

            @Override
            public float[] embed(Document document) {
                return new float[0];
            }
        };
        SpringAiModelRouter router = new SpringAiModelRouter(null, embeddings, policy(0), new RecordingMetrics(), 0,
                true, true, sleeps::add);
        router.embed(TaskType.EMBEDDING, List.of("hello"), false);
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().get("provider")).isEqualTo(Map.of("data_collection", "deny", "zdr", true));
    }

    @Test
    void modelWithoutAZdrEndpointIsSkippedAndTheNextCandidateServes() {
        SpringAiModelRouter router = router(model -> {
            if (model.equals("first")) {
                throw NotFoundException.builder().headers(Headers.builder().build())
                        .error(ErrorObject.builder().message(
                                "No endpoints found matching your data policy (Zero data retention)")
                                .type("invalid_request_error").param((String) null).code("404").build()).build();
            }
            return "from " + model;
        }, 2, true, true);
        ModelReply reply = router.chat(request(false));
        assertThat(reply.model()).isEqualTo("second");
        assertThat(reply.failedModels()).containsExactly("first");
        assertThat(calls).containsExactly("first", "second");
        assertThat(sleeps).isEmpty();
        assertThat(fallbacks).containsExactly("first:privacy_policy");
    }

    private ModelRequest request(boolean degrade) {
        return new ModelRequest(TaskType.CHAT, List.of(ChatMessage.user("hi")), List.of(), degrade, null, null);
    }

    private SpringAiModelRouter router(Function<String, String> behaviour, int retries) {
        return router(behaviour, retries, false, false);
    }

    private SpringAiModelRouter router(Function<String, String> behaviour, int retries, boolean denyDataCollection,
                                       boolean zeroDataRetention) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String m = ((OpenAiChatOptions) prompt.getOptions()).getModel();
                calls.add(m);
                sentExtraBodies.add(((OpenAiChatOptions) prompt.getOptions()).getExtraBody());
                return new ChatResponse(List.of(new Generation(new AssistantMessage(behaviour.apply(m)))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }
        };
        return new SpringAiModelRouter(model, null, policy(retries), new RecordingMetrics(), 0, denyDataCollection,
                zeroDataRetention, sleeps::add);
    }

    private static DefaultModelRoutingPolicy policy(int retries) {
        PriceTable prices = new PriceTable(Map.of(
                "first", new PriceTable.ModelPrice(new BigDecimal("3"), new BigDecimal("15")),
                "second", new PriceTable.ModelPrice(new BigDecimal("1"), new BigDecimal("5")),
                "third", new PriceTable.ModelPrice(new BigDecimal("0.1"), new BigDecimal("0.4"))),
                new PriceTable.ModelPrice(BigDecimal.TEN, BigDecimal.TEN));
        List<ModelRoute> routes = new ArrayList<>();
        for (TaskType t : TaskType.values()) {
            routes.add(new ModelRoute(t, List.of("first", "second", "third"), Duration.ofSeconds(2), retries,
                    Duration.ofMillis(10)));
        }
        return new DefaultModelRoutingPolicy(routes, prices);
    }

    private final class RecordingMetrics implements AiMetrics {

        @Override
        public void attempt(String task, String model, String outcome, Duration latency) {
        }

        @Override
        public void usage(String task, String model, TokenUsage usage, long costMicros) {
        }

        @Override
        public void fallback(String task, String fromModel, String reason) {
            fallbacks.add(fromModel + ":" + reason);
        }

        @Override
        public void budget(String feature, String verdict) {
        }

        @Override
        public void guardrail(String kind, int count) {
        }

        @Override
        public void toolCall(String tool, String outcome) {
        }
    }
}
