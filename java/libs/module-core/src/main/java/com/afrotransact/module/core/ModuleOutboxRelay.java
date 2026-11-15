package com.afrotransact.module.core;

import com.afrotransact.platform.events.OutboxRelay;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** Polls one module's outbox on that module's scheduler pool and inside that module's transaction. */
final class ModuleOutboxRelay implements ModuleOwned {

    private final String module;
    private final OutboxRelay relay;
    private final TransactionTemplate transaction;

    ModuleOutboxRelay(String module, OutboxRelay relay, TransactionTemplate transaction) {
        this.module = module;
        this.relay = relay;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${at.events.relay-interval:500ms}")
    void tick() {
        transaction.executeWithoutResult(status -> relay.relayBatch());
    }

    @Override
    public String module() {
        return module;
    }
}
