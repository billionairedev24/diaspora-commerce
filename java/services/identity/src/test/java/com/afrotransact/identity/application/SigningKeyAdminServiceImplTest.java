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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.identity.adapter.out.crypto.TestSecretCiphers;
import com.afrotransact.identity.domain.AuthSettings;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.OAuthClient;
import com.afrotransact.identity.domain.SigningKey;
import com.afrotransact.identity.domain.SigningKeyStatus;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OAuthClientAdminStore;
import com.afrotransact.identity.port.SecretCipher;
import com.afrotransact.identity.port.SecretCipherException;
import com.afrotransact.identity.port.SigningKeyGenerator;
import com.afrotransact.identity.port.SigningKeyStore;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SigningKeyAdminServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final String REASON = "Scheduled quarterly rotation";

    private final SigningKeyStore keys = mock(SigningKeyStore.class);
    private final SigningKeyGenerator generator = mock(SigningKeyGenerator.class);
    private final SecretCipher cipher = TestSecretCiphers.local("unit-1", "mB3FPeNA2n3gpPjBDhziub681AZitNXX4hoUn2t/IQ0=", "");
    private final OAuthClientAdminStore clients = mock(OAuthClientAdminStore.class);
    private final AuthSettingsService settings = mock(AuthSettingsService.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final SigningKeyAdminServiceImpl service = new SigningKeyAdminServiceImpl(keys, generator, cipher, clients,
            settings, permissions, changeLog, Clock.fixed(NOW, ZoneOffset.UTC));

    SigningKeyAdminServiceImplTest() {
        when(permissions.require(IdentityPermissions.IDENTITY_ADMIN)).thenReturn(new Actor("admin-1",
                PrincipalType.STAFF, Optional.empty(), Set.of("platform.super_admin"), Set.of("identity:admin")));
        when(settings.effective()).thenReturn(new AuthSettings(12, false, false, false, 30, 1440, null, null));
        when(generator.generate()).thenReturn(new SigningKeyGenerator.Generated("PUBLIC-PEM", "PRIVATE-PEM"));
    }

    private static OAuthClient clientWithAccessTtl(Duration ttl) {
        return new OAuthClient("c", "C", true, List.of("client_credentials"), List.of(), List.of(), List.of(), false,
                false, false, ttl, Duration.ofDays(1), true, NOW, null, null);
    }

    private static SigningKey key(String kid, SigningKeyStatus status, Instant retireAfter) {
        return new SigningKey(kid, "RS256", status, "PEM", NOW.minus(Duration.ofDays(90)), null, retireAfter, null);
    }

    @Test
    void rotationRetiresTheOldKeyAfterTheLongestAccessTokenAndActivatesTheNewOne() {
        when(keys.all()).thenReturn(List.of(key("old", SigningKeyStatus.ACTIVE, null)));
        when(clients.all()).thenReturn(List.of(clientWithAccessTtl(Duration.ofMinutes(5)),
                clientWithAccessTtl(Duration.ofMinutes(30))));

        SigningKey created = service.rotate(REASON);

        assertThat(created.status()).isEqualTo(SigningKeyStatus.ACTIVE);
        assertThat(created.kid()).startsWith("k20261024T100000-");
        Instant retireAfter = NOW.plus(Duration.ofMinutes(30)).plus(SigningKey.SKEW);
        verify(keys).markRetiring("old", NOW, retireAfter);
        ArgumentCaptor<byte[]> sealed = ArgumentCaptor.forClass(byte[].class);
        verify(keys).insertActive(eq(created), sealed.capture(), anyString(), eq("admin-1"));
        // the private half is stored sealed, bound to its row, and opens only there
        assertThat(cipher.open(sealed.getValue(), SigningKey.sealContext(created.kid()))).isEqualTo("PRIVATE-PEM");
        assertThatThrownBy(() -> cipher.open(sealed.getValue(), SigningKey.sealContext("another")))
                .isInstanceOf(SecretCipherException.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(changeLog).append(eq("admin-1"), eq("signing-key.rotated"), eq(null), eq(null), eq(created.kid()),
                detail.capture());
        assertThat(detail.getValue()).contains(REASON, "previous=old").doesNotContain("PRIVATE-PEM");
    }

    @Test
    void theTokenLifetimeCapShortensTheOverlap() {
        when(keys.all()).thenReturn(List.of(key("old", SigningKeyStatus.ACTIVE, null)));
        when(clients.all()).thenReturn(List.of(clientWithAccessTtl(Duration.ofMinutes(30))));
        when(settings.effective()).thenReturn(new AuthSettings(12, false, false, false, 30, 1440, 10, null));

        service.rotate(REASON);

        verify(keys).markRetiring(eq("old"), eq(NOW), eq(NOW.plus(Duration.ofMinutes(10)).plus(SigningKey.SKEW)));
    }

    @Test
    void listingRetiresKeysWhoseOverlapHasEnded() {
        when(keys.all()).thenReturn(List.of(key("current", SigningKeyStatus.ACTIVE, null),
                key("ended", SigningKeyStatus.RETIRING, NOW.minusSeconds(1)),
                key("overlap", SigningKeyStatus.RETIRING, NOW.plusSeconds(60))));

        service.list();

        verify(keys).markRetired("ended", NOW);
        verify(keys, never()).markRetired(eq("overlap"), any());
        verify(keys, never()).markRetired(eq("current"), any());
    }

    @Test
    void rotationNeedsAReasonAndChangesNothingWithoutOne() {
        assertThatThrownBy(() -> service.rotate("later")).isInstanceOf(IdentityRuleViolation.class);
        verify(keys, never()).insertActive(any(), any(), any(), any());
        verify(keys, never()).markRetiring(any(), any(), any());
    }

    @Test
    void startUpStoresTheConfiguredKeyOnlyWhenNoneIsStored() {
        when(keys.all()).thenReturn(List.of());
        service.seedIfEmpty("identity-1", "PUB", "PRIV");
        verify(keys).insertActive(any(SigningKey.class), any(byte[].class), anyString(), eq("system"));

        SigningKeyStore other = mock(SigningKeyStore.class);
        when(other.all()).thenReturn(List.of(key("k", SigningKeyStatus.ACTIVE, null)));
        new SigningKeyAdminServiceImpl(other, generator, cipher, clients, settings, permissions, changeLog,
                Clock.fixed(NOW, ZoneOffset.UTC)).seedIfEmpty("identity-1", "PUB", "PRIV");
        verify(other, never()).insertActive(any(), any(), any(), any());
    }
}
