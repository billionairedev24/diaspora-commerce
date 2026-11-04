package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.RecoveryResolution;
import com.afrotransact.payments.domain.TransferRecovery;
import com.afrotransact.payments.domain.TransferRecoveryStatus;
import com.afrotransact.payments.port.PaymentsMetrics;
import com.afrotransact.payments.port.TransferRecoveryRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class TransferRecoveryServiceImpl implements TransferRecoveryService {

    private static final int MAX_LIST = 500;

    private final TransferRecoveryRepository recoveries;
    private final LedgerPoster ledger;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final PaymentsMetrics metrics;
    private final Clock clock;

    TransferRecoveryServiceImpl(TransferRecoveryRepository recoveries, LedgerPoster ledger, MoneyTransactions tx,
                                PaymentsAccess access, PaymentsMetrics metrics, Clock clock) {
        this.recoveries = recoveries;
        this.ledger = ledger;
        this.tx = tx;
        this.access = access;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public List<TransferRecovery> list(TransferRecoveryStatus status, String orgId, int limit) {
        access.reader();
        return recoveries.list(status, orgId == null || orgId.isBlank() ? null : orgId.trim(),
                Math.max(1, Math.min(limit, MAX_LIST)));
    }

    @Override
    public TransferRecovery get(UUID id) {
        access.reader();
        return recoveries.find(id).orElseThrow(() -> notFound(id));
    }

    @Override
    public TransferRecovery propose(UUID id, RecoveryResolution resolution, String reason, String reference) {
        String actor = access.adjuster().subject();
        return tx.serializable(() -> {
            TransferRecovery current = recoveries.lock(id).orElseThrow(() -> notFound(id));
            return recoveries.update(current.propose(resolution, reason, reference, actor, Instant.now(clock)));
        });
    }

    @Override
    public TransferRecovery approve(UUID id, String note) {
        String actor = access.adjustmentApprover().subject();
        return tx.serializable(() -> {
            TransferRecovery current = recoveries.lock(id).orElseThrow(() -> notFound(id));
            Instant now = Instant.now(clock);
            Money amount = current.outstanding();
            TransferRecovery closed = current.approve(actor, note, now);
            ledger.post(journal(current, closed, amount, now), actor);
            metrics.transferRecovery(closed.status() == TransferRecoveryStatus.WRITTEN_OFF ? "written_off"
                    : "received");
            return recoveries.update(closed);
        });
    }

    @Override
    public TransferRecovery reject(UUID id, String note) {
        String actor = access.adjustmentApprover().subject();
        return tx.serializable(() -> {
            TransferRecovery current = recoveries.lock(id).orElseThrow(() -> notFound(id));
            return recoveries.update(current.reject(actor, note, Instant.now(clock)));
        });
    }

    private static Journal journal(TransferRecovery before, TransferRecovery closed, Money amount, Instant now) {
        UUID journal = Fingerprint.id("journal", "transfer-recovery-resolved", before.id().toString());
        String key = "transfer-recovery-resolved:" + before.id();
        return closed.resolution() == RecoveryResolution.WRITE_OFF
                ? Postings.transferRecoveryWrittenOff(journal, now, key, closed, amount)
                : Postings.transferRecoveryReceived(journal, now, key, closed, amount, closed.resolutionRef());
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("payments.transfer-recovery-not-found", "No transfer recovery " + id);
    }
}
