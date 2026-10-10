package com.afrotransact.payments.application;

import java.util.List;
import java.util.Optional;

import com.afrotransact.payments.domain.AccountBalance;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.port.LedgerRepository;
import org.springframework.stereotype.Service;

@Service
class LedgerServiceImpl implements LedgerService {

    private final LedgerRepository ledger;
    private final PaymentsAccess access;

    LedgerServiceImpl(LedgerRepository ledger, PaymentsAccess access) {
        this.ledger = ledger;
        this.access = access;
    }

    @Override
    public List<AccountBalance> balances() {
        access.reader();
        return ledger.balances(Optional.empty());
    }

    @Override
    public List<AccountBalance> balancesOfOrg(String orgId) {
        access.reader();
        return ledger.balances(Optional.of(orgId));
    }

    @Override
    public List<Journal> journalsOfOrder(String orderId) {
        access.reader();
        return ledger.journalsOfOrder(orderId);
    }
}
