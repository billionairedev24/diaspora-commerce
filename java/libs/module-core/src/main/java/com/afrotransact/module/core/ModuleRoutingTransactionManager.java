package com.afrotransact.module.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;

/**
 * The transaction manager that {@code @Transactional} finds when the application has one per module.
 *
 * <p>Spring's transaction interceptor asks the container for one {@code TransactionManager} and names the transaction after
 * the intercepted method ({@code com.afrotransact.catalog.application.FooImpl.bar}). The router reads the module from that
 * name and delegates to the module's own manager, so a {@code @Transactional} method always runs on its own module's database.
 * A name that belongs to no module is refused: guessing would put a write on the wrong database.
 */
final class ModuleRoutingTransactionManager implements PlatformTransactionManager {

    private final ModuleRegistry modules;
    private final BeanFactory beans;
    private final Map<TransactionStatus, PlatformTransactionManager> open = new ConcurrentHashMap<>();

    ModuleRoutingTransactionManager(ModuleRegistry modules, BeanFactory beans) {
        this.modules = modules;
        this.beans = beans;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        String name = definition == null ? null : definition.getName();
        ModuleDefinition module = name == null ? null : modules.moduleOfClassName(name).orElse(null);
        if (module == null) {
            throw new IllegalTransactionStateException("Cannot tell which module's database the transaction '" + name
                    + "' belongs to: @Transactional is only supported on classes inside a module's packages. "
                    + "Use the module's own TransactionTemplate elsewhere.");
        }
        PlatformTransactionManager delegate = beans.getBean(module.bean("transactionManager"),
                PlatformTransactionManager.class);
        TransactionStatus status = delegate.getTransaction(definition);
        open.put(status, delegate);
        return status;
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        delegateOf(status).commit(status);
        open.remove(status);
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        delegateOf(status).rollback(status);
        open.remove(status);
    }

    private PlatformTransactionManager delegateOf(TransactionStatus status) {
        PlatformTransactionManager delegate = open.get(status);
        if (delegate == null) {
            throw new IllegalTransactionStateException("Unknown transaction status: not started by this router");
        }
        return delegate;
    }
}
