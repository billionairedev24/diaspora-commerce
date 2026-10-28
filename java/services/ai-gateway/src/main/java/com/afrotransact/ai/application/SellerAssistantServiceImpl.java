package com.afrotransact.ai.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.afrotransact.ai.domain.AiFeature;
import com.afrotransact.ai.domain.AiRuleViolation;
import com.afrotransact.ai.domain.TaskType;
import com.afrotransact.ai.port.ChatMessage;
import com.afrotransact.ai.port.ModelReply;
import com.afrotransact.ai.port.ModelRequest;
import com.afrotransact.ai.port.ToolCallRequest;
import com.afrotransact.ai.port.ToolSpec;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;

@Service
class SellerAssistantServiceImpl implements SellerAssistantService {

    private static final Set<String> MEMBERS = Set.of("SELLER_MEMBER", "PROVIDER_MEMBER");
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{1,40}");
    private static final int MAX_MESSAGES = 48;
    private static final int MAX_TOOLS = 12;
    private static final int MAX_CONTENT = 24_000;
    private static final int DEFAULT_TOKENS = 900;
    private static final int MAX_TOKENS = 1_500;

    private final ModelAccessService models;

    SellerAssistantServiceImpl(ModelAccessService models) {
        this.models = models;
    }

    @Override
    public Reply complete(Caller caller, Request request) {
        caller.require(AiPermissions.USE);
        if (!MEMBERS.contains(caller.principalType()) || caller.orgId() == null || caller.orgId().isBlank()) {
            throw new ForbiddenActionException("ai.seller-only", "The seller assistant is for seller and provider members");
        }
        return turn(caller, request, AiFeature.SELLER_ASSISTANT);
    }

    @Override
    public Reply completeForStaff(Caller caller, Request request) {
        caller.require(AiPermissions.USE);
        if (!"STAFF".equals(caller.principalType())) {
            throw new ForbiddenActionException("ai.staff-only", "The staff assistant is for AfroTransact staff");
        }
        return turn(caller, request, AiFeature.STAFF_ASSISTANT);
    }

    private Reply turn(Caller caller, Request request, AiFeature feature) {
        List<Message> in = request.messages() == null ? List.of() : request.messages();
        if (in.isEmpty() || in.size() > MAX_MESSAGES) {
            throw new AiRuleViolation("ai.invalid-conversation", "Send between 1 and " + MAX_MESSAGES + " messages");
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (Message m : in) {
            messages.add(chatMessage(m));
        }
        List<Tool> offered = request.tools() == null ? List.of() : request.tools();
        if (offered.size() > MAX_TOOLS) {
            throw new AiRuleViolation("ai.too-many-tools", "At most " + MAX_TOOLS + " tools per turn");
        }
        List<ToolSpec> tools = new ArrayList<>();
        for (Tool t : offered) {
            if (t.name() == null || !TOOL_NAME.matcher(t.name()).matches() || t.inputSchema() == null) {
                throw new AiRuleViolation("ai.invalid-tool", "Tools need a snake_case name and an input schema");
            }
            tools.add(new ToolSpec(t.name(), t.description() == null ? "" : t.description(), t.inputSchema()));
        }
        int maxTokens = request.maxTokens() == null ? DEFAULT_TOKENS : Math.max(100, Math.min(MAX_TOKENS,
                request.maxTokens()));
        boolean fresh = messages.get(messages.size() - 1).role() == ChatMessage.Role.USER;
        ModelReply reply = models.chat(caller, feature, new ModelRequest(
                fresh ? TaskType.PLANNING : TaskType.CHAT, messages, tools, false, maxTokens, 0.3));
        List<ToolCall> calls = reply.toolCalls().stream()
                .map(c -> new ToolCall(c.id(), c.name(), c.arguments())).toList();
        return new Reply(reply.text(), calls, reply.usage().promptTokens(), reply.usage().completionTokens(),
                reply.model());
    }

    private static ChatMessage chatMessage(Message m) {
        String role = m.role() == null ? "" : m.role().trim().toLowerCase(Locale.ROOT);
        String content = m.content() == null ? "" : m.content();
        if (content.length() > MAX_CONTENT) {
            throw new AiRuleViolation("ai.message-too-long", "A message is limited to " + MAX_CONTENT + " characters");
        }
        return switch (role) {
            case "system" -> ChatMessage.system(content);
            case "user" -> ChatMessage.user(content);
            case "assistant" -> ChatMessage.assistant(content, m.toolCalls() == null ? List.of()
                    : m.toolCalls().stream().map(c -> new ToolCallRequest(c.id(), c.name(), c.arguments())).toList());
            case "tool" -> ChatMessage.tool(m.toolCallId(), m.toolName(), content);
            default -> throw new AiRuleViolation("ai.invalid-role", "Unknown message role '" + role + "'");
        };
    }
}
