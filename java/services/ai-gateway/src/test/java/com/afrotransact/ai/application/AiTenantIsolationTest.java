package com.afrotransact.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.afrotransact.ai.domain.guardrail.GroundingVerifier;
import com.afrotransact.ai.domain.guardrail.PiiRedactor;
import com.afrotransact.ai.domain.guardrail.TopicPolicy;
import com.afrotransact.ai.port.AiMetrics;
import com.afrotransact.ai.port.ConversationStore;
import com.afrotransact.ai.port.OutputModerator;
import com.afrotransact.ai.port.ToolSpec;
import com.afrotransact.ai.port.UsageLedger;
import com.afrotransact.platform.test.tenant.CrossTenant;
import org.junit.jupiter.api.Test;

/**
 * Cross-tenant proof for ai-gateway. Lola's tools take no customer, user or organisation id (only product, order and
 * booking references the model extracts from the chat) and every downstream call relays the caller's own bearer token,
 * so the owning service decides; conversation memory is keyed by (session, token subject); org-level data (usage) is
 * staff or service only.
 */
class AiTenantIsolationTest {

    private static final Caller CUSTOMER_1 = new Caller("cust-1", "CUSTOMER", null, Set.of("ai:use"), "tok-1");
    private static final Caller SELLER_B = new Caller("user-b", "SELLER_MEMBER", "org-b", Set.of("ai:use"), "tok-b");

    private final ConversationStore memory = mock(ConversationStore.class);

    private LolaServiceImpl lola() {
        return new LolaServiceImpl(mock(ModelAccessService.class), mock(LolaToolkit.class), memory,
                mock(HandoffRecorder.class), mock(TopicPolicy.class), mock(PiiRedactor.class),
                mock(GroundingVerifier.class), mock(OutputModerator.class), mock(AiSettings.class),
                mock(AiMetrics.class), Clock.systemUTC());
    }

    @Test
    void lolaHistoryIsReadForTheTokenSubjectOnly() {
        when(memory.recent("lola_session1", "cust-1", 0)).thenReturn(List.of());
        lola().history(CUSTOMER_1, "lola_session1");
        verify(memory).recent("lola_session1", "cust-1", 0);
        // another account naming the same session id asks the store for its own (empty) view
        lola().history(new Caller("cust-2", "CUSTOMER", null, Set.of(), null), "lola_session1");
        verify(memory).recent("lola_session1", "cust-2", 0);
        verify(memory, never()).recent(eq("lola_session1"), eq("someone-else"), anyInt());
    }

    @Test
    void noLolaToolTakesAnIdentityArgument() {
        for (ToolSpec spec : LolaToolSpecs.ALL) {
            assertThat(spec.inputSchema()).as(spec.name())
                    .doesNotContainIgnoringCase("customerId")
                    .doesNotContainIgnoringCase("userId")
                    .doesNotContainIgnoringCase("orgId")
                    .doesNotContainIgnoringCase("sellerOrg")
                    .doesNotContainIgnoringCase("accountId");
        }
    }

    @Test
    void sellerAssistantIsForMembersAndStaffAssistantForStaffOnly() {
        SellerAssistantServiceImpl assistant = new SellerAssistantServiceImpl(mock(ModelAccessService.class));
        SellerAssistantService.Request req = new SellerAssistantService.Request(List.of(), List.of(), null);
        CrossTenant.assertDenied(() -> assistant.complete(CUSTOMER_1, req));
        CrossTenant.assertDenied(() -> assistant.completeForStaff(SELLER_B, req));
    }

    @Test
    void orgUsageIsNotAvailableToSellerMembers() {
        UsageLedger ledger = mock(UsageLedger.class);
        OrgUsageServiceImpl usage = new OrgUsageServiceImpl(ledger);
        CrossTenant.assertDenied(() -> usage.usage(SELLER_B, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)));
        verify(ledger, never()).orgUsage(any(), any());
    }

    @Test
    void creditsAreBilledToTheTokenOrgNeverARequestedOne() {
        assertThat(ModelAccessServiceImpl.billableOrg(SELLER_B)).isEqualTo("org-b");
        assertThat(ModelAccessServiceImpl.billableOrg(CUSTOMER_1)).isNull();
    }
}
