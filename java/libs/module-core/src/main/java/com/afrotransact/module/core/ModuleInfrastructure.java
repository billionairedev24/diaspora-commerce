package com.afrotransact.module.core;

import java.time.Clock;
import java.util.function.Supplier;
import javax.sql.DataSource;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.EventSerializer;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.events.JdbcInboxDeduplicator;
import com.afrotransact.platform.events.JdbcOutboxStore;
import com.afrotransact.platform.events.KafkaOutboxRelay;
import com.afrotransact.platform.events.OutboxStore;
import com.afrotransact.platform.events.retention.EventRetentionProperties;
import com.afrotransact.platform.events.retention.EventRetentionServices;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers the beans every module owns once: its connection pool, migration, JDBC and transaction beans, outbox, inbox,
 * relay, retention and health. They are bean definitions with suppliers (not {@code @Bean} methods) because there is one set
 * per module and the set is not known until the configuration is read.
 */
final class ModuleInfrastructure {

    private final ModuleDefinition module;
    private final ModuleProperties properties;
    private final Environment environment;
    private final BeanDefinitionRegistry registry;
    private final BeanFactory beans;
    private final ClassLoader classLoader;

    ModuleInfrastructure(ModuleDefinition module, ModuleProperties properties, Environment environment,
                         BeanDefinitionRegistry registry, ClassLoader classLoader) {
        this.module = module;
        this.properties = properties;
        this.environment = environment;
        this.registry = registry;
        this.beans = (BeanFactory) registry;
        this.classLoader = classLoader;
    }

    void register() {
        if (properties.datasource().url() == null || properties.datasource().url().isBlank()) {
            return;
        }
        BeanDefinition dataSource = owned(BeanDefinitionBuilder.genericBeanDefinition(DataSource.class,
                () -> ModuleDataSources.create(module, properties.datasource())).getBeanDefinition());
        ((AbstractBeanDefinition) dataSource).setDestroyMethodName("close");
        registry.registerBeanDefinition(module.bean("dataSource"), dataSource);

        add("status", ModuleStatus.class, () -> ModuleMigration.run(module, properties, ds(), classLoader), null);
        String status = module.bean("status");
        add("jdbcClient", JdbcClient.class, () -> JdbcClient.create(ds()), status);
        add("jdbcTemplate", JdbcTemplate.class, () -> new JdbcTemplate(ds()), status);
        add("namedParameterJdbcTemplate", NamedParameterJdbcTemplate.class,
                () -> new NamedParameterJdbcTemplate(ds()), status);
        add("transactionManager", PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds()),
                status);
        add("transactionTemplate", TransactionTemplate.class, () -> new TransactionTemplate(tx()), status);

        add("outboxStore", OutboxStore.class, () -> new JdbcOutboxStore(jdbc()), status);
        add("inboxDeduplicator", InboxDeduplicator.class, () -> new JdbcInboxDeduplicator(jdbc()), status);
        add("domainEventPublisher", DomainEventPublisher.class,
                () -> new ModuleDomainEventPublisher(module.name(), ds(), beans.getBean(module.bean("outboxStore"),
                        OutboxStore.class), beans.getBean(EventSerializer.class), beans.getBean(Clock.class)), status);
        if (properties.events().relayEnabled()) {
            add("outboxRelay", ModuleOutboxRelay.class, () -> new ModuleOutboxRelay(module.name(),
                    new KafkaOutboxRelay(beans.getBean(module.bean("outboxStore"), OutboxStore.class), kafka(),
                            properties.events().relayBatchSize()), new TransactionTemplate(tx())), status);
        }
        if (properties.events().retentionEnabled()) {
            add("eventRetention", ModuleRetentionJob.class, () -> new ModuleRetentionJob(module.name(),
                    EventRetentionServices.jdbc(jdbc(), ds(),
                            Binder.get(environment).bindOrCreate("at.events.retention", EventRetentionProperties.class),
                            beans.getBean(Clock.class), scopedMeters())), status);
        }
        String key = ModuleCoreConfiguration.healthKey(module);
        registerHealth(key + "ModuleHealthIndicator", () -> new ModuleHealthIndicator(
                beans.getBean(module.bean("status"), ModuleStatus.class)));
        registerHealth(key + "DatabaseHealthIndicator", () -> new ModuleDatabaseHealthIndicator(ds()));
    }

    private <T> void add(String suffix, Class<T> type, Supplier<T> supplier, String dependsOn) {
        AbstractBeanDefinition definition = (AbstractBeanDefinition) owned(
                BeanDefinitionBuilder.genericBeanDefinition(type, supplier).getBeanDefinition());
        if (dependsOn != null) {
            definition.setDependsOn(dependsOn);
        }
        registry.registerBeanDefinition(module.bean(suffix), definition);
    }

    private void registerHealth(String beanName, Supplier<HealthIndicator> supplier) {
        registry.registerBeanDefinition(beanName, owned(
                BeanDefinitionBuilder.genericBeanDefinition(HealthIndicator.class, supplier).getBeanDefinition()));
    }

    private BeanDefinition owned(BeanDefinition definition) {
        definition.setAttribute(ModuleAttributes.MODULE, module.name());
        return definition;
    }

    private DataSource ds() {
        return beans.getBean(module.bean("dataSource"), DataSource.class);
    }

    private JdbcClient jdbc() {
        return beans.getBean(module.bean("jdbcClient"), JdbcClient.class);
    }

    private PlatformTransactionManager tx() {
        return beans.getBean(module.bean("transactionManager"), PlatformTransactionManager.class);
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafka() {
        return beans.getBean(KafkaTemplate.class);
    }

    /** A registry whose meters carry this module's tag, so a platform class that registers a meter by name is attributed. */
    private MeterRegistry scopedMeters() {
        CompositeMeterRegistry scoped = new CompositeMeterRegistry();
        scoped.config().commonTags("module", module.name());
        scoped.add(beans.getBean(MeterRegistry.class));
        return scoped;
    }
}
