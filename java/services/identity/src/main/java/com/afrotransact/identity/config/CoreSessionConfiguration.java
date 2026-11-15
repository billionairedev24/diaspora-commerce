package com.afrotransact.identity.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource;
import org.springframework.session.jdbc.config.annotation.SpringSessionTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Release 1 (ADR 0011), core only: Spring Session JDBC finds one DataSource and one transaction manager by type, and in core every
 * module has its own. The sessions live in identity's database, so they are named explicitly. Standalone, Spring Boot's single
 * DataSource is used and this class is not active.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${at.modules.core-service:}'.isEmpty()")
class CoreSessionConfiguration {

    @Bean
    @SpringSessionDataSource
    DataSource springSessionDataSource(@Qualifier("identity.dataSource") DataSource identityDataSource) {
        return identityDataSource;
    }

    @Bean
    @SpringSessionTransactionManager
    PlatformTransactionManager springSessionTransactionManager(
            @Qualifier("identity.transactionManager") PlatformTransactionManager identityTransactions) {
        return identityTransactions;
    }
}
