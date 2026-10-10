package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.ComplianceHistoryEntry;
import com.afrotransact.seller.domain.ComplianceStatus;
import com.afrotransact.seller.domain.ConnectAccount;

/** Connected accounts, their change history and sent reminders. */
public interface ConnectAccountRepository {

    /** Inserts unless the account exists; returns false on conflict. */
    boolean insert(ConnectAccount account);

    ConnectAccount update(ConnectAccount account);

    Optional<ConnectAccount> find(String accountId);

    Optional<ConnectAccount> lock(String accountId);

    Optional<ConnectAccount> findByOrg(String orgId);

    List<ConnectAccount> search(ComplianceStatus status, boolean openOnly, int limit);

    /** Accounts with a deadline before {@code before} and open requirements (reminder candidates). */
    List<ConnectAccount> withDeadlineBefore(Instant before, int limit);

    void appendHistory(ComplianceHistoryEntry entry);

    List<ComplianceHistoryEntry> history(String accountId, int limit);

    /** Records a scheduled reminder once per (account, deadline, threshold); false when already sent. */
    boolean claimReminder(String accountId, Instant deadline, int thresholdDays, Instant now);

    /** Records a manual reminder sent by staff. */
    void recordManualReminder(String accountId, String actorId, Instant now);
}
