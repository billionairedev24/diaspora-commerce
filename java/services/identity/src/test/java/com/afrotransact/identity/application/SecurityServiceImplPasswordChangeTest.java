package com.afrotransact.identity.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.PasswordPolicy;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.SessionStore;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;

/**
 * A changed password must end every other sign-in: refresh tokens live up to 30 days and are only re-validated
 * against the account (enabled flag, memberships), never against the credential.
 */
class SecurityServiceImplPasswordChangeTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PasswordPolicy passwordPolicy = mock(PasswordPolicy.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final SessionStore sessions = mock(SessionStore.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final UUID id = UUID.randomUUID();

    private SecurityServiceImpl service() {
        CurrentUser current = new CurrentUser(() -> new Actor(id.toString(), PrincipalType.CUSTOMER, Optional.empty(),
                Set.of(), Set.of()));
        return new SecurityServiceImpl(current, users, passwordPolicy, hasher, sessions, null, null, changeLog, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void changingThePasswordSignsOutEveryOtherDeviceButKeepsTheCurrentOne() {
        UserAccount u = new UserAccount(id, "Ada", "Okafor", "ada@example.com", true, null, "old-hash", true, false,
                NOW, null, NOW, NOW, 0);
        when(users.findById(id)).thenReturn(Optional.of(u));
        when(users.update(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));
        when(hasher.matches("old-pass", "old-hash")).thenReturn(true);
        when(hasher.hash("new-Pass-123")).thenReturn("new-hash");
        when(passwordPolicy.violations("new-Pass-123", List.of("Ada", "Okafor", "ada"))).thenReturn(List.of());

        service().changePassword("old-pass", "new-Pass-123", "new-Pass-123", Optional.of("current-access-token"));

        verify(sessions).revokeAll(id, Optional.of("current-access-token"));
        verify(sessions).endBrowserSessions(id);
    }

    @Test
    void aRejectedPasswordChangeRevokesNothing() {
        UserAccount u = new UserAccount(id, "Ada", "Okafor", "ada@example.com", true, null, "old-hash", true, false,
                NOW, null, NOW, NOW, 0);
        when(users.findById(id)).thenReturn(Optional.of(u));
        when(hasher.matches("wrong", "old-hash")).thenReturn(false);
        try {
            service().changePassword("wrong", "new-Pass-123", "new-Pass-123", Optional.of("t"));
        } catch (RuntimeException expected) {
            // 422 with field errors
        }
        verify(sessions, never()).revokeAll(any(), any());
    }
}
