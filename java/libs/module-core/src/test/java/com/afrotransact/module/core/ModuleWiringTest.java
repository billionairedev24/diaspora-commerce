package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;

import com.afrotransact.module.core.fixture.alpha.AlphaRepo;
import com.afrotransact.module.core.fixture.beta.BetaRepo;
import com.afrotransact.module.core.fixture.shared.PlatformCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Two modules, each with its own DataSource and transaction manager: a module class gets its own, platform code gets all. */
class ModuleWiringTest {

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void start() {
        ModuleRegistry registry = new ModuleRegistry("core");
        registry.add(new ModuleDefinition("alpha", "alpha", "com.afrotransact.module.core.fixture.alpha", "x.Alpha",
                List.of()), settings());
        registry.add(new ModuleDefinition("beta", "beta", "com.afrotransact.module.core.fixture.beta", "x.Beta",
                List.of()), settings());
        context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAutowireCandidateResolver(
                new ModuleAwareAutowireCandidateResolver(registry));
        for (String m : List.of("alpha", "beta")) {
            context.registerBeanDefinition(m + ".dataSource", owned(m, DataSource.class,
                    () -> new DriverManagerDataSource("jdbc:test:" + m)));
            context.registerBeanDefinition(m + ".tm", owned(m, PlatformTransactionManager.class, () -> new NamedTm(m)));
        }
        AbstractBeanDefinition router = (AbstractBeanDefinition) BeanDefinitionBuilder.genericBeanDefinition(
                PlatformTransactionManager.class, () -> new NamedTm("router")).getBeanDefinition();
        router.setPrimary(true);
        router.setAttribute(ModuleAttributes.MODULE, ModuleAttributes.ROUTER);
        context.registerBeanDefinition("transactionManager", router);
        context.register(AlphaRepo.class, BetaRepo.class, PlatformCode.class);
        context.refresh();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    @Test
    void aModuleClassGetsItsOwnDataSourceAndTransactionManager() {
        AlphaRepo alpha = context.getBean(AlphaRepo.class);
        BetaRepo beta = context.getBean(BetaRepo.class);
        assertThat(alpha.dataSource).isSameAs(context.getBean("alpha.dataSource"));
        assertThat(beta.dataSource).isSameAs(context.getBean("beta.dataSource"));
        assertThat(((NamedTm) alpha.transactions).name).isEqualTo("alpha");
        assertThat(((NamedTm) beta.transactions).name).isEqualTo("beta");
    }

    @Test
    void platformCodeSeesEveryDataSourceAndTheRouter() {
        PlatformCode platform = context.getBean(PlatformCode.class);
        assertThat(platform.dataSources).hasSize(2);
        assertThat(((NamedTm) platform.transactions).name).isEqualTo("router");
        assertThat(context.getBean(PlatformTransactionManager.class)).isSameAs(platform.transactions);
    }

    private static ModuleProperties settings() {
        return new ModuleProperties(true, new ModuleProperties.Database("jdbc:test", "u", "p", 4, 0,
                Duration.ofSeconds(10), Duration.ofMinutes(10)),
                new ModuleProperties.Migration(true, null), new ModuleProperties.Scheduler(2),
                new ModuleProperties.Events(true, 200, true), null, null);
    }

    private static <T> AbstractBeanDefinition owned(String module, Class<T> type, Supplier<T> supplier) {
        AbstractBeanDefinition definition = (AbstractBeanDefinition) BeanDefinitionBuilder.genericBeanDefinition(type, supplier)
                .getBeanDefinition();
        definition.setAttribute(ModuleAttributes.MODULE, module);
        return definition;
    }

    /** A transaction manager that only knows its name. */
    static final class NamedTm implements PlatformTransactionManager {

        final String name;

        NamedTm(String name) {
            this.name = name;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
