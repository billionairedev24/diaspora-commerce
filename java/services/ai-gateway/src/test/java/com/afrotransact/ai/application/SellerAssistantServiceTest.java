package com.afrotransact.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import com.afrotransact.ai.domain.AiFeature;
import com.afrotransact.ai.domain.AiRuleViolation;
import com.afrotransact.ai.domain.TaskType;
import com.afrotransact.ai.domain.TokenUsage;
import com.afrotransact.ai.port.ModelReply;
import com.afrotransact.ai.port.ModelRequest;
import com.afrotransact.ai.port.ToolCallRequest;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SellerAssistantServiceTest {

    private final ModelAccessService models = mock(ModelAccessService.class);
    private final SellerAssistantService service = new SellerAssistantServiceImpl(models);
    private final Caller seller = new Caller("user-1", "SELLER_MEMBER", "org_1", Set.of(AiPermissions.USE), null);

    private static SellerAssistantService.Request ask(String role) {
        return new SellerAssistantService.Request(List.of(
                new SellerAssistantService.Message("system", "You are Lola.", null, null, null),
                new SellerAssistantService.Message(role, "How many orders are waiting?", null, null, null)),
                List.of(new SellerAssistantService.Tool("my_orders", "Orders", "{\"type\":\"object\"}")), null);
    }

    @Test
    void routesAFreshQuestionToThePlannerAndReturnsTheToolCalls() {
        when(models.chat(any(), eq(AiFeature.SELLER_ASSISTANT), any())).thenReturn(new ModelReply("",
                List.of(new ToolCallRequest("c1", "my_orders", "{}")), new TokenUsage(120, 8), "model-x", 1, List.of()));

        SellerAssistantService.Reply reply = service.complete(seller, ask("user"));

        assertThat(reply.toolCalls()).extracting(SellerAssistantService.ToolCall::name).containsExactly("my_orders");
        assertThat(reply.promptTokens()).isEqualTo(120);
        ArgumentCaptor<ModelRequest> sent = ArgumentCaptor.forClass(ModelRequest.class);
        verify(models).chat(eq(seller), eq(AiFeature.SELLER_ASSISTANT), sent.capture());
        assertThat(sent.getValue().task()).isEqualTo(TaskType.PLANNING);
        assertThat(sent.getValue().tools()).hasSize(1);
        assertThat(sent.getValue().maxTokens()).isEqualTo(900);
    }

    @Test
    void anAnswerAfterToolResultsUsesTheChatRoute() {
        when(models.chat(any(), eq(AiFeature.SELLER_ASSISTANT), any())).thenReturn(new ModelReply("Two orders.",
                List.of(), new TokenUsage(10, 5), "model-x", 1, List.of()));
        service.complete(seller, ask("tool"));
        ArgumentCaptor<ModelRequest> sent = ArgumentCaptor.forClass(ModelRequest.class);
        verify(models).chat(eq(seller), eq(AiFeature.SELLER_ASSISTANT), sent.capture());
        assertThat(sent.getValue().task()).isEqualTo(TaskType.CHAT);
    }

    @Test
    void onlySellerAndProviderMembersWithAiUseMayAsk() {
        Caller customer = new Caller("user-2", "CUSTOMER", null, Set.of(AiPermissions.USE), null);
        assertThatThrownBy(() -> service.complete(customer, ask("user"))).isInstanceOf(ForbiddenActionException.class);
        Caller noPermission = new Caller("user-3", "SELLER_MEMBER", "org_1", Set.of(), null);
        assertThatThrownBy(() -> service.complete(noPermission, ask("user"))).isInstanceOf(ForbiddenActionException.class);
        Caller noOrg = new Caller("user-4", "SELLER_MEMBER", null, Set.of(AiPermissions.USE), null);
        assertThatThrownBy(() -> service.complete(noOrg, ask("user"))).isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void refusesUnknownRolesAndMalformedTools() {
        assertThatThrownBy(() -> service.complete(seller, ask("developer")))
                .isInstanceOf(AiRuleViolation.class).hasMessageContaining("role");
        SellerAssistantService.Request badTool = new SellerAssistantService.Request(
                List.of(new SellerAssistantService.Message("user", "hi", null, null, null)),
                List.of(new SellerAssistantService.Tool("Bad Name", "x", "{}")), null);
        assertThatThrownBy(() -> service.complete(seller, badTool)).isInstanceOf(AiRuleViolation.class);
    }
}
