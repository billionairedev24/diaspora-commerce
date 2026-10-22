package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.port.ConnectAccountRepository;
import com.afrotransact.seller.port.ConnectedAccountRegistry;
import com.afrotransact.seller.port.GatewayException;
import org.junit.jupiter.api.Test;

class ConnectedAccountSyncServiceImplTest {

    private final ConnectAccountRepository accounts = mock(ConnectAccountRepository.class);
    private final ConnectedAccountRegistry registry = mock(ConnectedAccountRegistry.class);
    private final AccessGuard access = mock(AccessGuard.class);
    private final ConnectedAccountSyncService service = new ConnectedAccountSyncServiceImpl(accounts, registry, access);

    private static ConnectAccount account(String accountId, String orgId) {
        ConnectAccount a = mock(ConnectAccount.class);
        when(a.accountId()).thenReturn(accountId);
        when(a.orgId()).thenReturn(orgId);
        return a;
    }

    @Test
    void registersEveryConnectedAccountAndSkipsOnesPaymentsRefuses() {
        ConnectAccount first = account("acct_1", "org_a");
        ConnectAccount second = account("acct_2", "org_b");
        when(accounts.connectedAfter(eq(""), anyInt())).thenReturn(List.of(first, second));
        when(accounts.connectedAfter(eq("acct_2"), anyInt())).thenReturn(List.of());
        doThrow(new GatewayException("seller.payments-unavailable", "down", false, null))
                .when(registry).register("org_a", "acct_1");

        int accepted = service.registerAll();

        assertThat(accepted).isEqualTo(1);
        verify(registry).register("org_b", "acct_2");
    }

    @Test
    void doesNothingWithoutAccounts() {
        when(accounts.connectedAfter(anyString(), anyInt())).thenReturn(List.of());

        assertThat(service.registerAll()).isZero();
    }

    @Test
    void staffRunChecksThePermissionFirst() {
        when(accounts.connectedAfter(anyString(), anyInt())).thenReturn(List.of());

        service.syncNow();

        verify(access).staff("compliance:remind");
    }
}
