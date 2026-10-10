package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.RoleKeys;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.EncryptedSecretRepository;
import com.afrotransact.identity.port.SecretCipher;
import com.afrotransact.identity.port.SecretCipherException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataProtectionServiceImplTest {

    private final SecretCipher cipher = mock(SecretCipher.class);
    private final EncryptedSecretRepository secrets = mock(EncryptedSecretRepository.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private Actor caller;
    private DataProtectionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DataProtectionServiceImpl(new CurrentUser(() -> caller), cipher, secrets, changeLog);
        when(cipher.currentKeyId()).thenReturn("kek-2");
        when(secrets.totpSecretsByKey()).thenReturn(Map.of("kek-2", 3L));
    }

    private static Actor staff(String... roles) {
        return new Actor(UUID.randomUUID().toString(), PrincipalType.STAFF, Optional.empty(), Set.of(roles), Set.of());
    }

    @Test
    void onlySuperAdminsSeeOrRotateKeys() {
        caller = staff("platform.developer");
        assertThatThrownBy(service::status).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(service::rotate).isInstanceOf(ForbiddenActionException.class);
        caller = new Actor("ops-bot", PrincipalType.SERVICE, Optional.empty(), Set.of(RoleKeys.SUPER_ADMIN),
                Set.of("*"));
        assertThatThrownBy(service::rotate).as("services never rotate keys").isInstanceOf(
                ForbiddenActionException.class);
        verifyNoInteractions(secrets, changeLog);

        caller = staff(RoleKeys.SUPER_ADMIN);
        assertThat(service.status().secretsByKey()).containsEntry("kek-2", 3L);
    }

    @Test
    void rotationWalksEveryPageCountsOutcomesAndIsAudited() {
        caller = staff(RoleKeys.SUPER_ADMIN);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(secrets.totpSecretsNotSealedUnder(eq("kek-2"), isNull(), anyInt())).thenReturn(List.of(a, b));
        when(secrets.totpSecretsNotSealedUnder("kek-2", b, 200)).thenReturn(List.of(c));
        when(secrets.totpSecretsNotSealedUnder("kek-2", c, 200)).thenReturn(List.of());
        when(secrets.resealTotp(a)).thenReturn(true);
        when(secrets.resealTotp(b)).thenReturn(false);
        when(secrets.resealTotp(c)).thenThrow(new SecretCipherException("Unknown data-protection key id kek-0"));

        ResealReport report = service.rotate();

        assertThat(report.keyId()).isEqualTo("kek-2");
        assertThat(report.resealed()).isEqualTo(1);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        verify(changeLog).append(eq(caller.subject()), eq("data-protection.rotated"), isNull(), isNull(),
                eq("mfa_totp.secret -> kek-2"), eq("resealed=1 skipped=1 failed=1"));
    }

    @Test
    void startUpMigrationOnlySealsPlaintextRowsWithoutACaller() {
        caller = null;
        UUID legacy = UUID.randomUUID();
        when(secrets.plaintextTotpSecrets(isNull(), anyInt())).thenReturn(List.of(legacy));
        when(secrets.plaintextTotpSecrets(eq(legacy), anyInt())).thenReturn(List.of());
        when(secrets.resealTotp(legacy)).thenReturn(true);

        ResealReport report = service.sealPlaintextSecrets();

        assertThat(report.resealed()).isEqualTo(1);
        verify(secrets, never()).totpSecretsNotSealedUnder(any(), any(), anyInt());
        verifyNoInteractions(changeLog);
    }
}
