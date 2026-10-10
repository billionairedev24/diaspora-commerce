package com.afrotransact.ai.adapter.in.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.afrotransact.ai.adapter.in.web.AiDtos.ChatReply;
import com.afrotransact.ai.adapter.in.web.AiDtos.ChatRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.EventView;
import com.afrotransact.ai.adapter.in.web.AiDtos.TurnView;
import com.afrotransact.ai.application.Caller;
import com.afrotransact.ai.application.LolaService;
import com.afrotransact.ai.domain.AiRuleViolation;
import com.afrotransact.ai.domain.BudgetExceededException;
import com.afrotransact.ai.domain.lola.LolaEvent;
import com.afrotransact.ai.port.ModelUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

@Tag(name = "Lola", description = "AI shopping agent: streaming chat with visible tool calls and action proposals")
@RestController
class LolaController {

    private static final Logger LOG = LoggerFactory.getLogger(LolaController.class);

    private final LolaService lola;
    private final CallerResolver callers;
    private final JsonMapper json;
    private final ExecutorService executor;
    private final Duration sseTimeout;

    LolaController(LolaService lola, CallerResolver callers, JsonMapper json,
                   @Qualifier("lolaExecutor") ExecutorService executor,
                   @Value("${at.ai.lola.sse-timeout:120s}") Duration sseTimeout) {
        this.lola = lola;
        this.callers = callers;
        this.json = json;
        this.executor = executor;
        this.sseTimeout = sseTimeout;
    }

    @Operation(summary = "Chat with Lola (Server-Sent Events)", description = """
            Streams one turn as SSE. Event names, in order: `tool_call` (what Lola is doing, e.g. "Searching for \
            jollof seasoning"), `tool_result` (product cards, offers, delivery promise, order status, services), \
            `action_proposal` (add to cart, checkout, book a service, start a return — the app performs it only \
            after the shopper's tap), `token` (answer text, grounded sentence by sentence), `handoff` (passed to \
            human support) and finally `done` (grounded message, cards, proposals, suggestion chips). On failure an \
            `error` event carries a problem code. Budget problems before the stream starts are returned as \
            RFC 9457 problems (429 `ai.budget-exceeded`).""",
            responses = @ApiResponse(responseCode = "200", description = "Event stream",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(implementation = EventView.class))))
    @PostMapping(path = "/v1/lola/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter chat(@Valid @RequestBody ChatRequest body, HttpServletRequest request) {
        Caller caller = callers.current(request);
        lola.admit(caller);
        SseEmitter emitter = new SseEmitter(sseTimeout.toMillis());
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        executor.execute(() -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                lola.chat(caller, body.toRequest(), event -> send(emitter, event));
                emitter.complete();
            } catch (UncheckedIOException e) {
                LOG.debug("Lola client disconnected");
                emitter.completeWithError(e);
            } catch (RuntimeException e) {
                failStream(emitter, e);
            } finally {
                MDC.clear();
            }
        });
        return emitter;
    }

    @Operation(summary = "Chat with Lola (single JSON response)",
            description = "Same turn as the streaming endpoint, returned at once: every event in order plus `done`.")
    @PostMapping(path = "/v1/lola/chat/complete", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ChatReply complete(@Valid @RequestBody ChatRequest body, HttpServletRequest request) {
        Caller caller = callers.current(request);
        List<EventView> events = new ArrayList<>();
        LolaEvent.Done done = lola.chat(caller, body.toRequest(), e -> events.add(EventView.of(e)));
        return new ChatReply(events, done);
    }

    @Operation(summary = "Stored turns of one of my Lola conversations (redacted)")
    @GetMapping("/v1/lola/sessions/{sessionId}/messages")
    List<TurnView> history(@PathVariable String sessionId, HttpServletRequest request) {
        return lola.history(callers.current(request), sessionId).stream()
                .map(t -> new TurnView(t.role(), t.content(), t.products(), t.at().toString())).toList();
    }

    private void send(SseEmitter emitter, LolaEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(json.writeValueAsString(event),
                    MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void failStream(SseEmitter emitter, RuntimeException e) {
        String code;
        String detail;
        if (e instanceof BudgetExceededException b) {
            code = BudgetExceededException.CODE;
            detail = b.getMessage();
        } else if (e instanceof ModelUnavailableException) {
            code = ModelUnavailableException.CODE;
            detail = "Lola is busy right now. Please try again in a moment.";
        } else if (e instanceof AiRuleViolation r) {
            code = r.code();
            detail = r.getMessage();
        } else {
            code = "ai.internal-error";
            detail = "Something went wrong. Please try again.";
            LOG.error("Lola turn failed", e);
        }
        try {
            send(emitter, new LolaEvent.Failure(code, detail));
            emitter.complete();
        } catch (UncheckedIOException ex) {
            emitter.completeWithError(ex);
        }
    }
}
