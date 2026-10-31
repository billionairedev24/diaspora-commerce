package com.afrotransact.platform.events.retention;

import java.time.Clock;
import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Daily purge of {@code platform_inbox} and published {@code platform_outbox} rows. On by default in every service
 * that has a database; opt out with {@code at.events.retention.enabled=false}. The properties are validated even when
 * the job is off, so an unsafe inbox retention never starts.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration")
@EnableScheduling
@EnableConfigurationProperties(EventRetentionProperties.class)
public class EventRetentionAutoConfiguration {

    @Bean
    @ConditionalOnBean({JdbcClient.class, DataSource.class})
    @ConditionalOnProperty(prefix = "at.events.retention", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean
    EventRetentionService eventRetentionService(JdbcClient jdbc, DataSource dataSource,
                                                EventRetentionProperties properties,
                                                ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
        return new JdbcEventRetentionService(jdbc, new PostgresAdvisoryRetentionLock(dataSource), properties,
                clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @ConditionalOnBean(EventRetentionService.class)
    EventRetentionJob eventRetentionJob(EventRetentionService retention) {
        return new EventRetentionJob(retention);
    }
}
