package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.port.ConnectedAccountRepository;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.payments.port.PaymentsMetrics;
import com.afrotransact.payments.port.PlanFeeWithholdingRepository;
import com.afrotransact.payments.port.SettlementRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /v1/settlement/transfers}: a member sees only their own organisation's transfers (whatever organisation
 * the request names), staff name the organisation and need payments:read or payouts:approve (and, with relationships on,
 * a grant covering it), and an order's transfers without an organisation are a staff view.
 */
class SettlementTransfersAccessTest {

    private static final SettlementTransfer TRANSFER = mock(SettlementTransfer.class);

    private final SettlementRepository settlements = mock(SettlementRepository.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final ResourceAccessChecker relationships = mock(ResourceAccessChecker.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private SettlementService service;

    @BeforeEach
    void service() {
        PaymentsAccess access = new PaymentsAccess(permissions, relationships, actors);
        service = new SettlementServiceImpl(settlements, mock(PaymentRepository.class), mock(LedgerRepository.class),
                mock(ConnectedAccountRepository.class), mock(StripeGateway.class), mock(LedgerPoster.class),
                mock(MoneyTransactions.class), access, mock(DomainEventPublisher.class), mock(PaymentsMetrics.class),
                null, mock(PlanFeeWithholdingRepository.class), mock(TransferRecoveryLedger.class),
                mock(MembershipBenefitsService.class), Clock.systemUTC());
        when(settlements.transfersOf(anyString(), anyInt())).thenReturn(List.of(TRANSFER));
        when(settlements.transfers(anyString(), anyString())).thenReturn(List.of(TRANSFER));
        when(settlements.transfersOfOrder(anyString())).thenReturn(List.of(TRANSFER));
    }

    private void as(PrincipalType type, String org, String... permissionKeys) {
        Actor actor = new Actor("u-1", type, Optional.ofNullable(org), Set.of(), Set.of(permissionKeys));
        when(actors.current()).thenReturn(actor);
        when(permissions.require(anyString())).thenReturn(actor);
    }

    @Test
    void aMemberSeesTheirOwnOrganisationsTransfersAndTheLimitIsClamped() {
        as(PrincipalType.SELLER_MEMBER, "org-a", "payouts:read");

        assertThat(service.transfers("org-a", null, 50)).containsExactly(TRANSFER);
        service.transfers("org-a", "  ", 0);
        service.transfers("org-a", "", 5000);

        verify(settlements).transfersOf("org-a", 50);
        verify(settlements).transfersOf("org-a", 1);
        verify(settlements).transfersOf("org-a", 200);
    }

    @Test
    void anOrderOfTheMembersOrganisationIsLookedUpWithItsOrganisation() {
        as(PrincipalType.SELLER_MEMBER, "org-a", "payouts:read");

        assertThat(service.transfers("org-a", " ord-1 ", 50)).containsExactly(TRANSFER);

        verify(settlements).transfers("ord-1", "org-a");
        verify(settlements, never()).transfersOf(anyString(), anyInt());
    }

    @Test
    void aMemberNamingAnotherOrganisationOnlyEverGetsTheirOwn() {
        as(PrincipalType.SELLER_MEMBER, "org-a", "payouts:read");

        service.transfers("org-b", null, 50);
        service.transfers("org-b", "ord-9", 50);

        verify(settlements).transfersOf("org-a", 50);
        verify(settlements).transfers("ord-9", "org-a");
        verify(settlements, never()).transfersOf("org-b", 50);
        verify(settlements, never()).transfers("ord-9", "org-b");
    }

    @Test
    void withRelationshipsOnAMemberNeedsToBeAllowedToViewPayouts() {
        as(PrincipalType.SELLER_MEMBER, "org-a", "payouts:read");
        when(relationships.relationshipsEnabled()).thenReturn(true);
        when(relationships.requireForOrg("payouts:read", PaymentsRelations.ORG_VIEW_PAYOUTS, "org-a"))
                .thenThrow(new ForbiddenActionException("auth.not-related", "not a finance member"));

        assertThatThrownBy(() -> service.transfers("org-a", null, 50)).isInstanceOf(ForbiddenActionException.class);

        verifyNoInteractions(settlements);
    }

    @Test
    void aNonStaffCallerWithoutAnOrganisationIsRefusedAndNoOrderIsRevealed() {
        as(PrincipalType.CUSTOMER, null, "payments:read");

        assertThatThrownBy(() -> service.transfers("org-a", null, 50)).isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("Only seller or provider organisations");
        assertThatThrownBy(() -> service.transfers(null, "ord-1", 50)).isInstanceOf(ForbiddenActionException.class);

        verifyNoInteractions(settlements);
    }

    @Test
    void staffNameTheOrganisationAndNeedPaymentsReadOrPayoutApproval() {
        as(PrincipalType.STAFF, null, "payments:read");

        assertThat(service.transfers(" org-b ", null, 50)).containsExactly(TRANSFER);
        verify(settlements).transfersOf("org-b", 50);

        as(PrincipalType.STAFF, null, "payouts:approve");
        assertThat(service.transfers("org-b", "ord-2", 50)).containsExactly(TRANSFER);
        verify(settlements).transfers("ord-2", "org-b");
    }

    @Test
    void staffWithoutAnyPaymentsPermissionAreRefused() {
        as(PrincipalType.STAFF, null, "orders:read");

        assertThatThrownBy(() -> service.transfers("org-b", null, 50)).isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("Missing permission");

        verifyNoInteractions(settlements);
    }

    @Test
    void withRelationshipsOnStaffNeedAGrantCoveringTheOrganisation() {
        as(PrincipalType.STAFF, null, "payments:read");
        when(relationships.relationshipsEnabled()).thenReturn(true);
        when(relationships.requireForOrg("payments:read", PaymentsRelations.ORG_VIEW_PAYOUTS, "org-b"))
                .thenThrow(new ForbiddenActionException("auth.not-related", "outside your market"));

        assertThatThrownBy(() -> service.transfers("org-b", null, 50)).isInstanceOf(ForbiddenActionException.class);

        verifyNoInteractions(settlements);
    }

    @Test
    void anOrdersTransfersWithoutAnOrganisationAreAStaffView() {
        as(PrincipalType.STAFF, null, "payments:read");
        when(relationships.require("payments:read", PaymentsRelations.PLATFORM_VIEW_ALL,
                FgaObject.PLATFORM)).thenReturn(
                new Actor("u-1", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("payments:read")));

        assertThat(service.transfers(null, " ord-3 ", 50)).containsExactly(TRANSFER);
        assertThat(service.transfers("", null, 50)).containsExactly(TRANSFER);

        verify(settlements).transfersOfOrder("ord-3");
        verify(settlements).transfersOfOrder("");
    }

    @Test
    void aMemberCannotReadAnOrderWithoutAnOrganisationEither() {
        as(PrincipalType.SELLER_MEMBER, "org-a", "payments:read");

        assertThatThrownBy(() -> service.transfers(null, "ord-3", 50)).isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("staff views");

        verifyNoInteractions(settlements);
    }
}
