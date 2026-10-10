package com.afrotransact.ai.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.ai.domain.AiFeature;
import com.afrotransact.ai.domain.AiRuleViolation;
import com.afrotransact.ai.domain.TaskType;
import com.afrotransact.ai.domain.TokenUsage;
import com.afrotransact.ai.domain.guardrail.GroundingFacts;
import com.afrotransact.ai.domain.guardrail.GroundingResult;
import com.afrotransact.ai.domain.guardrail.GroundingVerifier;
import com.afrotransact.ai.domain.guardrail.PiiRedactor;
import com.afrotransact.ai.domain.guardrail.Redaction;
import com.afrotransact.ai.domain.guardrail.TopicPolicy;
import com.afrotransact.ai.domain.guardrail.TopicVerdict;
import com.afrotransact.ai.domain.lola.ActionProposal;
import com.afrotransact.ai.domain.lola.LolaEvent;
import com.afrotransact.ai.domain.lola.ProductCard;
import com.afrotransact.ai.port.AiMetrics;
import com.afrotransact.ai.port.ChatMessage;
import com.afrotransact.ai.port.ConversationStore;
import com.afrotransact.ai.port.HandoffRepository;
import com.afrotransact.ai.port.ModelReply;
import com.afrotransact.ai.port.ModelRequest;
import com.afrotransact.ai.port.OutputModerator;
import com.afrotransact.ai.port.ToolCallRequest;
import com.afrotransact.ai.port.ToolSpec;
import org.springframework.stereotype.Service;

@Service
class LolaServiceImpl implements LolaService {

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final String NOTHING_GROUNDED = "I couldn't confirm those details in our catalogue, so I've left "
            + "them out. Could you tell me a bit more about what you're looking for?";

    private final ModelAccessService models;
    private final LolaToolkit toolkit;
    private final ConversationStore memory;
    private final HandoffRecorder handoffs;
    private final TopicPolicy topics;
    private final PiiRedactor redactor;
    private final GroundingVerifier grounding;
    private final OutputModerator moderator;
    private final AiSettings settings;
    private final AiMetrics metrics;
    private final Clock clock;

    LolaServiceImpl(ModelAccessService models, LolaToolkit toolkit, ConversationStore memory, HandoffRecorder handoffs,
                    TopicPolicy topics, PiiRedactor redactor, GroundingVerifier grounding, OutputModerator moderator,
                    AiSettings settings, AiMetrics metrics, Clock clock) {
        this.models = models;
        this.toolkit = toolkit;
        this.memory = memory;
        this.handoffs = handoffs;
        this.topics = topics;
        this.redactor = redactor;
        this.grounding = grounding;
        this.moderator = moderator;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void admit(Caller caller) {
        models.admit(caller, AiFeature.LOLA);
    }

    @Override
    public List<ConversationStore.Turn> history(Caller caller, String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            return List.of();
        }
        return memory.recent(sessionId, caller.subject(), settings.memoryTurns());
    }

    @Override
    public LolaEvent.Done chat(Caller caller, LolaRequest request, LolaEventSink sink) {
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            throw new AiRuleViolation("ai.empty-message", "Type a message for Lola");
        }
        if (message.length() > settings.maxInputChars()) {
            throw new AiRuleViolation("ai.input-too-long", "Messages are limited to " + settings.maxInputChars()
                    + " characters");
        }
        String sessionId = request.sessionId() != null && SESSION_ID.matcher(request.sessionId()).matches()
                ? request.sessionId() : "lola_" + UUID.randomUUID().toString().replace("-", "");
        Turn turn = new Turn(sessionId, caller, sink);
        List<ConversationStore.Turn> history = memory.recent(sessionId, caller.subject(), settings.memoryTurns());
        history.forEach(t -> turn.facts.addProducts(t.products()));
        turn.redaction = redactor.redact(message);
        metrics.guardrail("pii_redacted", turn.redaction.placeholders().size());

        TopicVerdict verdict = topics.assess(message);
        if (!verdict.allowed()) {
            metrics.guardrail("topic_" + verdict.category().name().toLowerCase(Locale.ROOT), 1);
            return answerWithoutModel(turn, verdict, history);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(LolaPrompts.system(LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC),
                request.zip(), request.language())));
        for (ConversationStore.Turn t : history) {
            messages.add("user".equals(t.role()) ? ChatMessage.user(t.content())
                    : ChatMessage.assistant(t.content(), List.of()));
        }
        messages.add(ChatMessage.user(turn.redaction.text()));

        for (int round = 0; ; round++) {
            boolean lastRound = round >= settings.maxToolRounds();
            List<ToolSpec> tools = lastRound ? List.of() : toolkit.specs();
            GroundedTextStream text = new GroundedTextStream(grounding, turn.facts, moderator);
            ModelReply reply = models.stream(caller, AiFeature.LOLA, new ModelRequest(
                    round == 0 ? TaskType.PLANNING : TaskType.CHAT, messages, tools, false,
                    settings.maxCompletionTokens(), 0.3), delta -> turn.emitText(text.accept(delta)));
            turn.emitText(text.finish());
            turn.absorb(reply, text);
            if (reply.toolCalls().isEmpty() || lastRound) {
                break;
            }
            messages.add(ChatMessage.assistant(reply.text(), reply.toolCalls()));
            for (ToolCallRequest call : reply.toolCalls()) {
                messages.add(runTool(turn, call, request.zip()));
            }
        }
        return finish(turn, message, history);
    }

    private ChatMessage runTool(Turn turn, ToolCallRequest call, String zip) {
        LolaToolkit.Invocation invocation = toolkit.describe(call);
        turn.sink.emit(new LolaEvent.ToolCall(call.id(), call.name(), turn.redaction.restore(invocation.label()),
                invocation.arguments()));
        turn.toolsUsed.add(call.name());
        ToolCallRequest forExecution = new ToolCallRequest(call.id(), call.name(),
                turn.redaction.restore(call.arguments()));
        ToolOutcome outcome = toolkit.execute(forExecution, new ToolContext(turn.caller, turn.sessionId, zip,
                turn.facts));
        turn.sink.emit(new LolaEvent.ToolResult(call.id(), call.name(), outcome.kind(), outcome.data()));
        if (outcome.proposal() != null) {
            turn.proposals.add(outcome.proposal());
            turn.sink.emit(new LolaEvent.Proposal(outcome.proposal()));
        }
        if (outcome.handoff() != null && !turn.handedOff) {
            HandoffRepository.Handoff h = handoffs.record(turn.caller, turn.sessionId, outcome.handoff().reason(),
                    outcome.handoff().summary());
            turn.handedOff = true;
            turn.sink.emit(new LolaEvent.Handoff(h.id().toString(), h.reason(), h.summary()));
        }
        // Tool output may contain the shopper's own details (addresses on orders): redact again before the model.
        turn.redaction = redactor.redact(outcome.modelContent(), turn.redaction);
        return ChatMessage.tool(call.id(), call.name(), turn.redaction.text());
    }

    private LolaEvent.Done answerWithoutModel(Turn turn, TopicVerdict verdict, List<ConversationStore.Turn> history) {
        turn.emitText(verdict.reply());
        boolean handoff = verdict.category() == TopicVerdict.Category.HUMAN_REQUESTED
                || (verdict.category() == TopicVerdict.Category.UNSAFE && verdict.reply().contains("988"));
        if (handoff) {
            List<ConversationStore.Turn> transcript = new ArrayList<>(history);
            transcript.add(new ConversationStore.Turn("user", turn.redaction.text(), List.of(), Instant.now(clock)));
            HandoffRepository.Handoff h = handoffs.recordWithSummary(turn.caller, turn.sessionId,
                    verdict.category().name().toLowerCase(Locale.ROOT), transcript);
            turn.handedOff = true;
            turn.sink.emit(new LolaEvent.Handoff(h.id().toString(), h.reason(), h.summary()));
        }
        return finish(turn, turn.redaction.text(), history);
    }

    private LolaEvent.Done finish(Turn turn, String userMessage, List<ConversationStore.Turn> history) {
        if (turn.answer.toString().isBlank()) {
            turn.emitText(turn.dropped.isEmpty() && turn.facts.products().isEmpty() && turn.proposals.isEmpty()
                    ? "Sorry, I couldn't find anything for that. Could you rephrase it?" : turn.dropped.isEmpty()
                    ? "Here's what I found." : NOTHING_GROUNDED);
        }
        String redactedAnswer = turn.answer.toString().trim();
        List<ProductCard> remembered = new ArrayList<>(turn.cards.values());
        turn.facts.products().stream().filter(c -> !turn.cards.containsKey(c.atin())).limit(20)
                .forEach(remembered::add);
        Instant now = Instant.now(clock);
        memory.append(turn.sessionId, turn.caller.subject(), List.of(
                new ConversationStore.Turn("user", redactor.redact(userMessage).text(), List.of(), now),
                new ConversationStore.Turn("assistant", redactedAnswer, remembered, now.plusMillis(1))));
        if (!turn.dropped.isEmpty()) {
            metrics.guardrail("grounding_dropped", turn.dropped.size());
        }
        LolaEvent.Done done = new LolaEvent.Done(turn.sessionId, turn.redaction.restore(redactedAnswer),
                List.copyOf(turn.cards.values()), turn.proposals,
                LolaSuggestions.after(turn.toolsUsed, turn.handedOff),
                turn.dropped.stream().map(d -> Map.of("kind", d.kind(), "value", d.value())).toList(),
                List.copyOf(turn.models), turn.usage.promptTokens(), turn.usage.completionTokens());
        turn.sink.emit(done);
        return done;
    }

    /** Mutable state of one shopper turn. */
    private static final class Turn {

        private final String sessionId;
        private final Caller caller;
        private final LolaEventSink sink;
        private final GroundingFacts facts = new GroundingFacts();
        private final StringBuilder answer = new StringBuilder();
        private final Map<String, ProductCard> cards = new LinkedHashMap<>();
        private final List<GroundingResult.DroppedClaim> dropped = new ArrayList<>();
        private final List<ActionProposal> proposals = new ArrayList<>();
        private final Set<String> toolsUsed = new LinkedHashSet<>();
        private final Set<String> models = new LinkedHashSet<>();
        private TokenUsage usage = TokenUsage.NONE;
        private Redaction redaction;
        private boolean handedOff;

        Turn(String sessionId, Caller caller, LolaEventSink sink) {
            this.sessionId = sessionId;
            this.caller = caller;
            this.sink = sink;
        }

        /** Emits grounded, moderated text (redacted form kept for memory; restored for the shopper's display). */
        void emitText(String groundedText) {
            if (groundedText == null || groundedText.isEmpty()) {
                return;
            }
            answer.append(groundedText);
            sink.emit(new LolaEvent.Token(redaction.restore(groundedText)));
        }

        void absorb(ModelReply reply, GroundedTextStream text) {
            usage = usage.plus(reply.usage());
            models.add(reply.model());
            text.cards().forEach(c -> cards.putIfAbsent(c.atin(), c));
            dropped.addAll(text.dropped());
        }
    }
}
