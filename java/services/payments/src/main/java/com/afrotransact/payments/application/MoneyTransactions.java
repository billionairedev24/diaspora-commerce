package com.afrotransact.payments.application;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactions that move money run at SERIALIZABLE isolation (architecture §5: "SERIALIZABLE isolation only inside
 * the ledger") and are retried on serialization failures (SQLSTATE 40001), deadlocks (40P01) and optimistic-lock
 * conflicts. Stripe calls inside them always carry idempotency keys derived from business ids, so a retry replays
 * Stripe's first answer instead of moving money twice.
 */
@Component
class MoneyTransactions {

    private static final Logger LOG = LoggerFactory.getLogger(MoneyTransactions.class);
    private static final int MAX_ATTEMPTS = 10;

    private final TransactionTemplate serializable;
    private final TransactionTemplate requiresNew;

    MoneyTransactions(PlatformTransactionManager transactionManager) {
        this.serializable = new TransactionTemplate(transactionManager);
        this.serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs {@code work} in a SERIALIZABLE transaction (joining one that is already serializable), with retries. */
    <T> T serializable(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!inSerializable()) {
                throw new IllegalStateException("Money movements must not join a non-serializable transaction");
            }
            return work.get();
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return serializable.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (!retryable(e) || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                LOG.debug("Serialization conflict (attempt {}), retrying: {}", attempt, e.getMessage());
                backoff(attempt);
            }
        }
    }

    /** Runs {@code work} in its own READ COMMITTED transaction (bookkeeping of failures). */
    <T> T requiresNew(Supplier<T> work) {
        return requiresNew.execute(status -> work.get());
    }

    static boolean inSerializable() {
        Integer level = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        return TransactionSynchronizationManager.isActualTransactionActive() && level != null
                && level == TransactionDefinition.ISOLATION_SERIALIZABLE;
    }

    static boolean retryable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConcurrencyFailureException) {
                return true;
            }
            if (t instanceof SQLException sql && ("40001".equals(sql.getSQLState())
                    || "40P01".equals(sql.getSQLState()))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static void backoff(int attempt) {
        long max = Math.min(200, 5L << Math.min(attempt, 5));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, max + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a transaction", ie);
        }
    }
}
