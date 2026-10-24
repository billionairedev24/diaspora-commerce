package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.identity.domain.AuthSettings;
import com.afrotransact.identity.domain.AuthSettingsOverrides;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.AuthSettingsStore;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthSettingsServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final AuthSettings DEFAULTS = new AuthSettings(12, false, false, false, 30, 1440, null, null);
    private static final String REASON = "Raise the bar after the credential-stuffing wave";

    private final AuthSettingsStore store = mock(AuthSettingsStore.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final AuthSettingsServiceImpl service = new AuthSettingsServiceImpl(store, DEFAULTS, permissions,
            changeLog, clock);

    AuthSettingsServiceImplTest() {
        when(permissions.require(IdentityPermissions.IDENTITY_ADMIN)).thenReturn(new Actor("admin-1",
                PrincipalType.STAFF, Optional.empty(), Set.of("platform.super_admin"), Set.of("identity:admin")));
        when(store.load()).thenReturn(AuthSettingsOverrides.none());
    }

    @Test
    void withNothingStoredTheConfiguredDefaultsApply() {
        assertThat(service.effective()).isEqualTo(DEFAULTS);
    }

    @Test
    void anUpdateIsValidatedStoredAuditedAndReadAtRuntime() {
        AuthSettingsOverrides o = new AuthSettingsOverrides(16, null, true, null, null, null, 10, null);
        when(store.load()).thenReturn(AuthSettingsOverrides.none());

        AuthSettingsService.AuthSettingsView view = service.update(o, REASON);

        assertThat(view.effective().passwordMinLength()).isEqualTo(16);
        assertThat(view.effective().mfaRequiredSeller()).isTrue();
        assertThat(view.effective().accessTokenTtlCapMinutes()).isEqualTo(10);
        assertThat(view.defaults()).isEqualTo(DEFAULTS);
        verify(store).save(o, NOW, "admin-1");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(changeLog).append(eq("admin-1"), eq("auth-settings.updated"), eq(null), eq(null), eq("auth-settings"),
                detail.capture());
        assertThat(detail.getValue()).contains(REASON, "before=passwordMin=12", "after=passwordMin=16");

        // the next read sees the stored value
        when(store.load()).thenReturn(o);
        assertThat(service.effective().passwordMinLength()).isEqualTo(16);
    }

    @Test
    void invalidSettingsAreRefusedAndNothingIsStored() {
        AuthSettingsOverrides o = new AuthSettingsOverrides(3, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.update(o, REASON)).isInstanceOf(IdentityRuleViolation.class);
        verify(store, never()).save(any(), any(), any());
        verify(changeLog, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aChangeNeedsAReason() {
        assertThatThrownBy(() -> service.update(AuthSettingsOverrides.none(), "x"))
                .isInstanceOf(IdentityRuleViolation.class);
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void runtimeReadsAreCachedBrieflyAndPickUpChangesFromOtherReplicas() {
        service.effective();
        service.effective();
        verify(store, times(1)).load();

        AuthSettingsOverrides stored = new AuthSettingsOverrides(20, null, null, null, null, null, null, null);
        when(store.load()).thenReturn(stored);
        clock.advance(Duration.ofSeconds(6));
        assertThat(service.effective().passwordMinLength()).isEqualTo(20);
    }

    @Test
    void viewsAndUpdatesNeedIdentityAdmin() {
        when(permissions.require(IdentityPermissions.IDENTITY_ADMIN)).thenThrow(
                new ForbiddenActionException("auth.missing-permission", "Missing permission identity:admin"));
        assertThatThrownBy(service::view).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> service.update(AuthSettingsOverrides.none(), REASON))
                .isInstanceOf(ForbiddenActionException.class);
        // runtime readers (sign-in, password policy) are not gated: they are the platform itself
        assertThat(service.effective()).isEqualTo(DEFAULTS);
    }
}
