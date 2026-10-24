package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.AdminSession;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.OAuthClient;
import com.afrotransact.identity.domain.TokenInspection;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.DiscoveryReader;
import com.afrotransact.identity.port.JwtInspector;
import com.afrotransact.identity.port.OAuthClientAdminStore;
import com.afrotransact.identity.port.SessionAdminStore;
import com.afrotransact.identity.port.SessionStore;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionAndInspectorServicesTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final String REASON = "Laptop reported stolen by the owner";
    private static final Actor ADMIN = new Actor("admin-1", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("identity:admin"));

    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SessionAdminStore sessions = mock(SessionAdminStore.class);
    private final SessionStore browserSessions = mock(SessionStore.class);
    private final OAuthClientAdminStore clients = mock(OAuthClientAdminStore.class);
    private final SessionAdminServiceImpl sessionService = new SessionAdminServiceImpl(sessions, browserSessions,
            clients, permissions, changeLog, clock);
    private final JwtInspector jwtInspector = mock(JwtInspector.class);
    private final DiscoveryReader discovery = mock(DiscoveryReader.class);
    private final TokenInspectorServiceImpl inspector = new TokenInspectorServiceImpl(jwtInspector, discovery,
            new TokenIssuer("https://id.test"), permissions, changeLog, clock);

    SessionAndInspectorServicesTest() {
        when(permissions.require(IdentityPermissions.IDENTITY_ADMIN)).thenReturn(ADMIN);
    }

    private static AdminSession session(String id, UUID user) {
        return new AdminSession(id, user.toString(), "Ada Obi", "ada@example.com", "customer-app", "Customer app",
                "203.0.113.9", NOW.minusSeconds(900), NOW.minusSeconds(60), NOW.plusSeconds(86_400), true);
    }

    @Test
    void searchPassesTheFiltersAndClampsThePageSize() {
        UUID user = UUID.randomUUID();
        when(sessions.search(user.toString(), "customer-app", "ada", NOW, 100, 200))
                .thenReturn(new SessionAdminStore.Page(List.of(session("s1", user)), 201));

        PageView<AdminSession> page = sessionService.search(" ada ", user, "customer-app", 2, 5_000);

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(201);
        assertThat(page.size()).isEqualTo(100);
    }

    @Test
    void revokingOneSessionDeletesItAndIsAudited() {
        UUID user = UUID.randomUUID();
        when(sessions.find("s1")).thenReturn(Optional.of(session("s1", user)));

        sessionService.revoke("s1", REASON);

        verify(sessions).revoke("s1");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(changeLog).append(eq("admin-1"), eq("session.revoked"), eq(user), eq(null), eq("customer-app"),
                detail.capture());
        assertThat(detail.getValue()).contains(REASON, "session=s1");
    }

    @Test
    void signOutEverywhereRevokesEveryAuthorizationAndTheBrowserSessions() {
        UUID user = UUID.randomUUID();
        when(sessions.revokeAllOfUser(user.toString())).thenReturn(3);
        when(browserSessions.endBrowserSessions(user)).thenReturn(2);

        SessionAdminService.Revoked r = sessionService.revokeAllOfUser(user, REASON);

        assertThat(r).isEqualTo(new SessionAdminService.Revoked(3, 2));
        verify(changeLog).append(eq("admin-1"), eq("sessions.revoked-user"), eq(user), eq(null), eq(user.toString()),
                anyString());
    }

    @Test
    void revokingAClientsSessionsNeedsAKnownClientAndAReason() {
        when(clients.find("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sessionService.revokeAllOfClient("ghost", REASON))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sessionService.revokeAllOfClient("customer-app", "no"))
                .isInstanceOf(IdentityRuleViolation.class);
        verify(sessions, never()).revokeAllOfClient(any());

        when(clients.find("customer-app")).thenReturn(Optional.of(mock(OAuthClient.class)));
        when(sessions.revokeAllOfClient("customer-app")).thenReturn(7);
        assertThat(sessionService.revokeAllOfClient("customer-app", REASON).sessions()).isEqualTo(7);
    }

    @Test
    void anUnknownSessionIsNotFound() {
        when(sessions.find("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sessionService.revoke("gone", REASON)).isInstanceOf(NotFoundException.class);
        verify(sessions, never()).revoke(any());
    }

    @Test
    void theInspectorChecksTheIssuerAuditsWhoLookedAndNeverTheToken() {
        String secretToken = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEifQ.c2ln";
        when(jwtInspector.inspect(secretToken, "https://id.test", NOW)).thenReturn(new TokenInspection(true, Map.of(),
                Map.of("sub", "user-1"), List.of(), "k1", null, null, null, true, true, true, List.of()));

        TokenInspection result = inspector.inspect("  " + secretToken + " ");

        assertThat(result.valid()).isTrue();
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(changeLog).append(eq("admin-1"), eq("token.inspected"), eq(null), eq(null), eq("jwt"), detail.capture());
        assertThat(detail.getValue()).contains("sub=user-1", "kid=k1").doesNotContain(secretToken)
                .doesNotContain("eyJ");
    }

    @Test
    void emptyOrHugeInputIsRefusedBeforeAnythingIsParsed() {
        assertThatThrownBy(() -> inspector.inspect("   ")).isInstanceOf(IdentityRuleViolation.class);
        assertThatThrownBy(() -> inspector.inspect("a".repeat(TokenInspectorService.MAX_TOKEN_LENGTH + 1)))
                .isInstanceOf(IdentityRuleViolation.class);
        verify(jwtInspector, never()).inspect(any(), any(), any());
    }

    @Test
    void everythingNeedsIdentityAdmin() {
        when(permissions.require(IdentityPermissions.IDENTITY_ADMIN)).thenThrow(
                new ForbiddenActionException("auth.missing-permission", "Missing permission identity:admin"));
        assertThatThrownBy(() -> sessionService.search(null, null, null, 0, 10))
                .isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> inspector.inspect("a.b.c")).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(inspector::documents).isInstanceOf(ForbiddenActionException.class);
    }
}
