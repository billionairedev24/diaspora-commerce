package com.afrotransact.module.core;

import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.EventEnvelope;
import com.afrotransact.platform.events.EventSerializer;
import com.afrotransact.platform.events.OutboxStore;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The module's outbox publisher. Same contract as {@code OutboxDomainEventPublisher} (an event is written in the caller's
 * transaction and never outside one) but the transaction is checked against THIS module's database, because the platform
 * class's {@code @Transactional(MANDATORY)} cannot tell which module's transaction manager it belongs to.
 */
final class ModuleDomainEventPublisher implements DomainEventPublisher, ModuleOwned {

    private final String module;
    private final DataSource dataSource;
    private final OutboxStore store;
    private final EventSerializer serializer;
    private final Clock clock;

    ModuleDomainEventPublisher(String module, DataSource dataSource, OutboxStore store, EventSerializer serializer,
                               Clock clock) {
        this.module = module;
        this.dataSource = dataSource;
        this.store = store;
        this.serializer = serializer;
        this.clock = clock;
    }

    @Override
    public void publish(String type, String aggregateType, String aggregateId, Object payload) {
        Object holder = TransactionSynchronizationManager.getResource(dataSource);
        if (!(holder instanceof ConnectionHolder connection) || !connection.isSynchronizedWithTransaction()) {
            throw new IllegalTransactionStateException("Module '" + module + "': event " + type
                    + " must be published inside a transaction of the module's own database");
        }
        store.append(new EventEnvelope(UUID.randomUUID(), type, aggregateType, aggregateId, clock.instant(),
                serializer.toJson(payload)));
    }

    @Override
    public String module() {
        return module;
    }
}
