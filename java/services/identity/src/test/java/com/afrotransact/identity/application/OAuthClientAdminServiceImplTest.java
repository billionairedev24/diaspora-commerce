package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.identity.application.OAuthClientAdminService.ClientDraft;
import com.afrotransact.identity.application.OAuthClientAdminService.ClientWithSecret;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.OAuthClient;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OAuthClientAdminStore;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OAuthClientAdminServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final String REASON = "Onboarding the partner portal";

    private final OAuthClientAdminStore store = mock(OAuthClientAdminStore.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final OAuthClientAdminServiceImpl service = new OAuthClientAdminServiceImpl(store, hasher, permissions,
            changeLog, new ProtectedClients(Set.of("gateway-bff")), Clock.fixed(NOW, ZoneOffset.UTC));

    OAuthClientAdminServiceImplTest() {
        when(permissions.require(IdentityPermissions.IDENTITY_ADMIN)).thenReturn(new Actor("admin-1",
                PrincipalType.STAFF, Optional.empty(), Set.of("platform.super_admin"), Set.of("identity:admin")));
        when(hasher.hash(anyString())).thenAnswer(i -> "{bcrypt}hash-of-" + i.getArgument(0));
    }

    private static ClientDraft draft() {
        return new ClientDraft("Partner portal", List.of("authorization_code", "refresh_token"),
                List.of("openid", "profile"), List.of("https://partners.example.com/callback"), List.of(), false,
                false, Duration.ofMinutes(5), Duration.ofDays(30));
    }

    private static OAuthClient existing(String id, boolean confidential, boolean enabled) {
        return new OAuthClient(id, "Existing", confidential, List.of("authorization_code"), List.of("openid"),
                List.of("https://x.example.com/cb"), List.of(), true, false, false, Duration.ofMinutes(5),
                Duration.ofDays(30), enabled, NOW.minusSeconds(86_400), null, null);
    }

    @Test
    void creatingAConfidentialClientShowsTheSecretOnceAndStoresOnlyItsHash() {
        when(store.find("partner-portal")).thenReturn(Optional.empty());

        ClientWithSecret created = service.create(draft(), "partner-portal", true, REASON);

        assertThat(created.secret()).hasSizeGreaterThan(30);
        assertThat(created.toString()).doesNotContain(created.secret());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(store).create(any(OAuthClient.class), hash.capture(), eq(NOW), eq("admin-1"));
        assertThat(hash.getValue()).isEqualTo("{bcrypt}hash-of-" + created.secret());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(changeLog).append(eq("admin-1"), eq("oauth-client.created"), eq(null), eq(null), eq("partner-portal"),
                detail.capture());
        assertThat(detail.getValue()).contains(REASON).doesNotContain(created.secret());
    }

    @Test
    void aPublicClientGetsNoSecretAndAlwaysUsesPkce() {
        when(store.find("partner-app")).thenReturn(Optional.empty());

        ClientWithSecret created = service.create(draft(), "partner-app", false, REASON);

        assertThat(created.secret()).isNull();
        assertThat(created.client().requirePkce()).isTrue();
        verify(store).create(any(OAuthClient.class), eq(null), eq(NOW), eq("admin-1"));
    }

    @Test
    void everyChangeNeedsAReason() {
        assertThatThrownBy(() -> service.create(draft(), "partner-portal", true, " too short "))
                .isInstanceOf(IdentityRuleViolation.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> service.rotateSecret("gateway-bff", null)).isInstanceOf(IdentityRuleViolation.class);
        assertThatThrownBy(() -> service.setEnabled("gateway-bff", true, "")).isInstanceOf(IdentityRuleViolation.class);
        verify(store, never()).create(any(), any(), any(), any());
        verify(changeLog, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void invalidFieldsAreReportedPerField() {
        ClientDraft bad = new ClientDraft("", List.of("password"), List.of("open id"), List.of("javascript:alert(1)"),
                List.of(), false, false, Duration.ofHours(5), Duration.ofDays(30));
        assertThatThrownBy(() -> service.create(bad, "Bad Id", true, REASON)).isInstanceOfSatisfying(
                IdentityRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKeys("clientId", "name",
                        "grantTypes", "scopes", "accessTokenTtlMinutes"));
    }

    @Test
    void anExistingClientIdIsAConflict() {
        when(store.find("partner-portal")).thenReturn(Optional.of(existing("partner-portal", true, true)));
        assertThatThrownBy(() -> service.create(draft(), "partner-portal", true, REASON))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void rotatingASecretKeepsTheOldOneForTheGracePeriodAndNeverLogsEither() {
        when(store.find("billing-svc")).thenReturn(Optional.of(existing("billing-svc", true, true)));

        ClientWithSecret rotated = service.rotateSecret("billing-svc", REASON);

        verify(store).replaceSecret("billing-svc", "{bcrypt}hash-of-" + rotated.secret(), NOW.plus(Duration.ofHours(24)),
                NOW, "admin-1");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(changeLog).append(eq("admin-1"), eq("oauth-client.secret-rotated"), eq(null), eq(null),
                eq("billing-svc"), detail.capture());
        assertThat(detail.getValue()).doesNotContain(rotated.secret()).contains("valid until");
    }

    @Test
    void aPublicClientHasNoSecretToRotate() {
        when(store.find("app")).thenReturn(Optional.of(existing("app", false, true)));
        assertThatThrownBy(() -> service.rotateSecret("app", REASON)).isInstanceOf(IdentityRuleViolation.class);
        verify(store, never()).replaceSecret(any(), any(), any(), any(), any());
    }

    @Test
    void thePlatformsOwnSignInClientCannotBeDisabled() {
        when(store.find("gateway-bff")).thenReturn(Optional.of(existing("gateway-bff", true, true)));
        assertThatThrownBy(() -> service.setEnabled("gateway-bff", false, REASON))
                .isInstanceOf(IdentityRuleViolation.class).hasMessageContaining("lock");
        verify(store, never()).setEnabled(any(), anyBoolean(), any(), any());
    }

    @Test
    void disablingAndEnablingAreAuditedWithBeforeAndAfter() {
        when(store.find("billing-svc")).thenReturn(Optional.of(existing("billing-svc", true, true)));
        service.setEnabled("billing-svc", false, REASON);
        verify(store).setEnabled("billing-svc", false, NOW, "admin-1");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(changeLog).append(eq("admin-1"), eq("oauth-client.disabled"), eq(null), eq(null), eq("billing-svc"),
                detail.capture());
        assertThat(detail.getValue()).contains("before enabled=true").contains("after enabled=false");
    }

    @Test
    void editingWritesBeforeAndAfterToTheAccessLog() {
        when(store.find("billing-svc")).thenReturn(Optional.of(existing("billing-svc", true, true)));
        service.update("billing-svc", draft(), REASON);
        ArgumentCaptor<OAuthClient> saved = ArgumentCaptor.forClass(OAuthClient.class);
        verify(store).update(saved.capture());
        assertThat(saved.getValue().redirectUris()).containsExactly("https://partners.example.com/callback");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(changeLog).append(eq("admin-1"), eq("oauth-client.updated"), eq(null), eq(null), eq("billing-svc"),
                detail.capture());
        assertThat(detail.getValue()).contains("before=").contains("after=").contains("Partner portal");
    }

    @Test
    void withoutIdentityAdminNothingHappens() {
        when(permissions.require(IdentityPermissions.IDENTITY_ADMIN)).thenThrow(
                new ForbiddenActionException("auth.missing-permission", "Missing permission identity:admin"));
        assertThatThrownBy(service::list).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> service.create(draft(), "x-client", true, REASON))
                .isInstanceOf(ForbiddenActionException.class);
        verify(store, never()).create(any(), any(), any(), any());
    }
}
