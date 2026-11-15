package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessChange;
import com.afrotransact.identity.domain.AccessLogFilter;
import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.ModuleAccessRepository;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.SessionStore;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.identity.domain.PasswordPolicy;
import com.afrotransact.identity.domain.PhoneNumberPolicy;
import com.afrotransact.identity.domain.SignUpPolicy;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;

class AccessAdminServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final Actor READER = new Actor("reader-1", PrincipalType.STAFF, Optional.empty(), Set.of(),
            Set.of("access:read"));
    private static final Actor NOBODY = new Actor("nobody-1", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of());

    private final CurrentUser current = mock(CurrentUser.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final AccessAdminServiceImpl service = new AccessAdminServiceImpl(current, mock(PermissionChecker.class),
            users, mock(UserAccessRepository.class), mock(RoleRepository.class), mock(ModuleAccessRepository.class),
            changeLog, mock(AccessProfileLoader.class), mock(AccessPolicy.class), mock(SessionStore.class),
            mock(MfaRepository.class), mock(SignUpPolicy.class), mock(PasswordPolicy.class),
            mock(PhoneNumberPolicy.class), mock(PasswordHasher.class), mock(DomainEventPublisher.class),
            new MutableClock(NOW));

    AccessAdminServiceImplTest() {
        when(current.actor()).thenReturn(READER);
    }

    @Test
    void peopleSearchPassesTheRoleFilterToTheQueryAndReturnsTheExactTotal() {
        when(users.search("ada", "platform.customer_support", 25, 50)).thenReturn(List.of());
        when(users.count("ada", "platform.customer_support")).thenReturn(61L);

        PageView<UserAccessView> page = service.users(" ada ", " platform.customer_support ", 2, 25);

        assertThat(page.total()).isEqualTo(61);
        assertThat(page.page()).isEqualTo(2);
        verify(users).search("ada", "platform.customer_support", 25, 50);
    }

    @Test
    void aBlankRoleMeansNoRoleFilter() {
        when(users.search("", null, 50, 0)).thenReturn(List.of());

        service.users(null, "  ", 0, 50);

        verify(users).search("", null, 50, 0);
        verify(users).count("", null);
    }

    @Test
    void theAccessLogFilterCombinesPersonRoleAndPermissionAndCountsExactly() {
        UUID person = UUID.randomUUID();
        AccessLogFilter filter = new AccessLogFilter(person, "platform.dispatcher", " delivery:read ");
        AccessChange change = new AccessChange(7, NOW, "actor", "role.permission.granted", null,
                "platform.dispatcher", "delivery:read", "Dispatchers need run read");
        when(changeLog.search(filter, 20, 40)).thenReturn(List.of(change));
        when(changeLog.count(filter)).thenReturn(41L);

        PageView<AccessChange> page = service.changeLog(filter, 2, 20);

        assertThat(filter.permission()).isEqualTo("delivery:read");
        assertThat(page.items()).containsExactly(change);
        assertThat(page.total()).isEqualTo(41);
    }

    @Test
    void sizeIsCappedSoOneRequestCannotReadTheWholeLog() {
        when(changeLog.search(any(), eq(200), eq(0)))
                .thenReturn(List.of());

        assertThat(service.changeLog(AccessLogFilter.none(), 0, 10_000).size()).isEqualTo(200);
    }

    @Test
    void readingTheFilteredLogNeedsAccessRead() {
        when(current.actor()).thenReturn(NOBODY);

        assertThatThrownBy(() -> service.changeLog(AccessLogFilter.none(), 0, 50))
                .isInstanceOf(ForbiddenActionException.class);
        verify(changeLog, never()).search(any(), anyInt(),
                anyInt());
    }
}
