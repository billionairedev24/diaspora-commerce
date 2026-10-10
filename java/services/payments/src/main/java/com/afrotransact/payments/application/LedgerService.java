package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.AccountBalance;
import com.afrotransact.payments.domain.Journal;

/** Read side of the double-entry ledger (staff, payments:read). */
public interface LedgerService {

    /** Balance of every (sub-)account per currency. */
    List<AccountBalance> balances();

    /** Balances of the lines attributed to one seller / provider organisation. */
    List<AccountBalance> balancesOfOrg(String orgId);

    List<Journal> journalsOfOrder(String orderId);
}
