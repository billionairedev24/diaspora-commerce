package com.afrotransact.mcp.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.afrotransact.mcp.port.AuditSpool;
import com.afrotransact.mcp.port.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Broker first, spool on failure. While this pod knows the spool holds events it sends new ones to the spool too, so
 * they queue behind the backlog instead of overtaking it; the replay clears that state once the spool is empty.
 * If the spool is unavailable as well, the {@code com.afrotransact.mcp.audit} log line written by the guard is the
 * last copy (shipped by the log pipeline).
 */
@Service
class AuditRecorderServiceImpl implements AuditRecorderService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditRecorderServiceImpl.class);

    private final AuditTrail broker;
    private final AuditSpool spool;
    private final int batchSize;
    private final Duration publishTimeout;
    private final Duration lease;
    private final String owner = "mcp-" + UUID.randomUUID();
    private final AtomicBoolean backlog = new AtomicBoolean();

    AuditRecorderServiceImpl(AuditTrail broker, AuditSpool spool, AuditSettings settings) {
        this.broker = broker;
        this.spool = spool;
        this.batchSize = settings.replayBatch();
        this.publishTimeout = settings.publishTimeout();
        this.lease = settings.replayLease();
    }

    @Override
    public void record(AuditTrail.ToolInvoked event) {
        if (backlog.get()) {
            toSpool(event, "backlog pending");
            return;
        }
        try {
            broker.publish(event).whenComplete((ok, failure) -> {
                if (failure != null) {
                    backlog.set(true);
                    toSpool(event, failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            backlog.set(true);
            toSpool(event, e.getClass().getSimpleName());
        }
    }

    @Override
    public int replaySpool() {
        try {
            if (!spool.tryLease(owner, lease)) {
                return 0;
            }
        } catch (RuntimeException e) {
            LOG.debug("audit spool unavailable for replay ({})", e.getClass().getSimpleName());
            return 0;
        }
        int replayed = 0;
        try {
            while (true) {
                List<AuditSpool.Spooled> batch = spool.oldest(batchSize);
                if (batch.isEmpty()) {
                    backlog.set(false);
                    return replayed;
                }
                for (AuditSpool.Spooled entry : batch) {
                    if (!publishNow(entry.event())) {
                        return replayed;
                    }
                    spool.remove(entry.spoolId());
                    replayed++;
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("audit replay interrupted after {} events ({})", replayed, e.getClass().getSimpleName());
            return replayed;
        } finally {
            if (replayed > 0) {
                LOG.info("replayed {} spooled mcp.tool.invoked events", replayed);
            }
            releaseQuietly();
        }
    }

    private boolean publishNow(AuditTrail.ToolInvoked event) {
        try {
            broker.publish(event).toCompletableFuture().get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.debug("broker still refusing audit events ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    private void toSpool(AuditTrail.ToolInvoked event, String reason) {
        try {
            spool.append(event);
        } catch (RuntimeException e) {
            LOG.error("mcp.tool.invoked {} neither published ({}) nor spooled ({}); only the audit log has it",
                    event.eventId(), reason, e.getClass().getSimpleName());
        }
    }

    private void releaseQuietly() {
        try {
            spool.releaseLease(owner);
        } catch (RuntimeException e) {
            LOG.debug("audit replay lease not released ({}); it expires on its own", e.getClass().getSimpleName());
        }
    }
}
