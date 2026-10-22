package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.port.ConnectAccountRepository;
import com.afrotransact.seller.port.ConnectedAccountRegistry;
import com.afrotransact.seller.port.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class ConnectedAccountSyncServiceImpl implements ConnectedAccountSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectedAccountSyncServiceImpl.class);
    private static final int PAGE = 200;

    private final ConnectAccountRepository accounts;
    private final ConnectedAccountRegistry registry;

    ConnectedAccountSyncServiceImpl(ConnectAccountRepository accounts, ConnectedAccountRegistry registry) {
        this.accounts = accounts;
        this.registry = registry;
    }

    @Override
    public int registerAll() {
        int accepted = 0;
        String after = "";
        List<ConnectAccount> page = accounts.connectedAfter(after, PAGE);
        while (!page.isEmpty()) {
            for (ConnectAccount a : page) {
                try {
                    registry.register(a.orgId(), a.accountId());
                    accepted++;
                } catch (GatewayException ex) {
                    LOG.warn("Organisation {}: payments did not accept Connect account {} ({}); trying again later",
                            a.orgId(), a.accountId(), ex.getMessage());
                }
            }
            after = page.get(page.size() - 1).accountId();
            page = accounts.connectedAfter(after, PAGE);
        }
        return accepted;
    }
}
