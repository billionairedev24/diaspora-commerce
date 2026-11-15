package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** {@code @Transactional} runs on the database of the module whose class it is on, and nowhere else. */
class ModuleRoutingTransactionManagerTest {

    private final List<String> log = new ArrayList<>();
    private final ModuleRegistry registry = new ModuleRegistry("core");
    private final ModuleRoutingTransactionManager router;

    ModuleRoutingTransactionManagerTest() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        ModuleProperties settings = new ModuleProperties(true, new ModuleProperties.Database("u", "u", "p", 4, 0,
                Duration.ofSeconds(1), Duration.ofSeconds(1)), new ModuleProperties.Migration(true, null),
                new ModuleProperties.Scheduler(1), new ModuleProperties.Events(true, 1, true), null, null);
        for (String name : List.of("catalog", "tax-returns")) {
            String base = name.equals("catalog") ? "com.afrotransact.catalog" : "com.afrotransact.taxreturns";
            registry.add(new ModuleDefinition(name, name, base, "x.App", List.of()), settings);
            beans.registerSingleton(name + ".transactionManager", new Recording(name, log));
        }
        router = new ModuleRoutingTransactionManager(registry, beans);
    }

    private static DefaultTransactionDefinition named(String name) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName(name);
        return definition;
    }

    @Test
    void aTransactionalMethodUsesItsOwnModulesManagerForBeginAndEnd() {
        TransactionStatus a = router.getTransaction(named("com.afrotransact.catalog.application.FooImpl.save"));
        TransactionStatus b = router.getTransaction(named("com.afrotransact.taxreturns.application.BarImpl.decide"));
        router.commit(b);
        router.rollback(a);
        assertThat(log).containsExactly("catalog:begin", "tax-returns:begin", "tax-returns:commit",
                "catalog:rollback");
    }

    @Test
    void aMethodOfNoModuleIsRefusedInsteadOfGuessing() {
        assertThatThrownBy(() -> router.getTransaction(named("com.afrotransact.platform.events.Thing.run")))
                .isInstanceOf(IllegalTransactionStateException.class).hasMessageContaining("which module's database");
        assertThatThrownBy(() -> router.getTransaction(new DefaultTransactionDefinition()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aStatusItDidNotStartIsRefused() {
        assertThatThrownBy(() -> router.commit(new SimpleTransactionStatus()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** Records which module's manager was used. */
    record Recording(String module, List<String> log) implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            log.add(module + ":begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            log.add(module + ":commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            log.add(module + ":rollback");
        }
    }
}
