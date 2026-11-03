package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.SessionAudience;
import com.afrotransact.identity.domain.SessionPolicy;
import com.afrotransact.identity.domain.SessionPolicyOverrides;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.SessionPolicyStore;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionPolicyServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final String REASON = "Staff asked for a shorter console session";
    private static final Actor ADMIN = new Actor("admin-1", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("identity:admin"));
    private static final Actor BFF = new Actor("gateway-bff", PrincipalType.SERVICE, Optional.empty(), Set.of(),
            Set.of("sessions:policy.read"));
    private static final Actor SHOPPER = new Actor("user-1", PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of());

    private final SessionPolicyStore store = mock(SessionPolicyStore.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final CurrentUser current = mock(CurrentUser.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final SessionPolicyServiceImpl service = new SessionPolicyServiceImpl(store, permissions, current,
            changeLog, new MutableClock(NOW));

    SessionPolicyServiceImplTest() {
        when(permissions.require(IdentityPermissions.IDENTITY_ADMIN)).thenReturn(ADMIN);
        when(current.actor()).thenReturn(BFF);
        when(store.loadAll()).thenReturn(Map.of());
    }

    @Test
    void withNothingStoredEveryAudienceHasItsBuiltInDefault() {
        Map<SessionAudience, SessionPolicy> all = service.effective();

        assertThat(all).hasSize(SessionAudience.values().length);
        assertThat(all.get(SessionAudience.STAFF)).isEqualTo(new SessionPolicy(15, 480, 60));
        assertThat(all.get(SessionAudience.DRIVER)).isEqualTo(new SessionPolicy(840, 840, 0));
    }

    @Test
    void storedOverridesWinOverTheDefaultForThatAudienceOnly() {
        when(store.loadAll()).thenReturn(Map.of(SessionAudience.STAFF, new SessionPolicyOverrides(10, null, null)));

        Map<SessionAudience, SessionPolicy> all = service.effective();

        assertThat(all.get(SessionAudience.STAFF)).isEqualTo(new SessionPolicy(10, 480, 60));
        assertThat(all.get(SessionAudience.SELLER)).isEqualTo(new SessionPolicy(30, 720, 60));
    }

    @Test
    void onlyAServiceWithTheScopeOrStaffAdminMayReadTheEffectivePolicy() {
        when(current.actor()).thenReturn(SHOPPER);
        assertThatThrownBy(service::effective).isInstanceOf(ForbiddenActionException.class);

        when(current.actor()).thenReturn(ADMIN);
        assertThat(service.effective()).isNotEmpty();
    }

    @Test
    void anUpdateIsValidatedStoredWithoutDefaultsAndAudited() {
        SessionPolicyOverrides o = new SessionPolicyOverrides(10, 480, null);

        SessionPolicyService.SessionPolicyEntry entry = service.update(SessionAudience.STAFF, o, REASON);

        assertThat(entry.effective()).isEqualTo(new SessionPolicy(10, 480, 60));
        assertThat(entry.overrides()).isEqualTo(new SessionPolicyOverrides(10, null, null));
        verify(store).save(SessionAudience.STAFF, new SessionPolicyOverrides(10, null, null), NOW, "admin-1");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(changeLog).append(eq("admin-1"), eq("session-policy.updated"), eq(null), eq(null),
                eq("session-policy:staff"), detail.capture());
        assertThat(detail.getValue()).contains(REASON, "before=idle=15m absolute=480m warn=60s",
                "after=idle=10m absolute=480m warn=60s");
    }

    @Test
    void savingEverythingAtTheDefaultStoresUseTheDefault() {
        SessionPolicyService.SessionPolicyEntry entry = service.update(SessionAudience.STAFF,
                new SessionPolicyOverrides(15, 480, 60), REASON);

        assertThat(entry.overrides().isEmpty()).isTrue();
        verify(store).save(SessionAudience.STAFF, SessionPolicyOverrides.none(), NOW, "admin-1");
    }

    @Test
    void invalidPoliciesAreRefusedAndNothingIsStoredOrAudited() {
        SessionPolicyOverrides tooLong = new SessionPolicyOverrides(600, null, null);
        assertThatThrownBy(() -> service.update(SessionAudience.STAFF, tooLong, REASON))
                .isInstanceOf(IdentityRuleViolation.class);
        verify(store, never()).save(any(), any(), any(), any());
        verify(changeLog, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aChangeNeedsAReason() {
        assertThatThrownBy(() -> service.update(SessionAudience.STAFF, SessionPolicyOverrides.none(), "x"))
                .isInstanceOf(IdentityRuleViolation.class);
        verify(store, never()).save(any(), any(), any(), any());
    }

    @Test
    void viewsAndUpdatesNeedIdentityAdmin() {
        when(permissions.require(IdentityPermissions.IDENTITY_ADMIN)).thenThrow(
                new ForbiddenActionException("auth.missing-permission", "Missing permission identity:admin"));
        assertThatThrownBy(service::view).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> service.update(SessionAudience.STAFF, SessionPolicyOverrides.none(), REASON))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void theViewListsEveryAudienceWithItsDefault() {
        assertThat(service.view()).extracting(SessionPolicyService.SessionPolicyEntry::audience)
                .containsExactly(SessionAudience.values());
    }
}
