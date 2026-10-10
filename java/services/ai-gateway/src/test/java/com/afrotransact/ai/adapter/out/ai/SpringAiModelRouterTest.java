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
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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

    private ModelRequest request(boolean degrade) {
        return new ModelRequest(TaskType.CHAT, List.of(ChatMessage.user("hi")), List.of(), degrade, null, null);
    }

    private SpringAiModelRouter router(Function<String, String> behaviour, int retries) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String m = ((OpenAiChatOptions) prompt.getOptions()).getModel();
                calls.add(m);
                return new ChatResponse(List.of(new Generation(new AssistantMessage(behaviour.apply(m)))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }
        };
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
        return new SpringAiModelRouter(model, null, new DefaultModelRoutingPolicy(routes, prices), new RecordingMetrics(),
                0, sleeps::add);
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
