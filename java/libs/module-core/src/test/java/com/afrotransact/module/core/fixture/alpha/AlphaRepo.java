package com.afrotransact.module.core.fixture.alpha;

import javax.sql.DataSource;

import org.springframework.transaction.PlatformTransactionManager;

/** Fixture: a module class that injects "the" DataSource and "the" transaction manager. */
public class AlphaRepo {

    public final DataSource dataSource;
    public final PlatformTransactionManager transactions;

    public AlphaRepo(DataSource dataSource, PlatformTransactionManager transactions) {
        this.dataSource = dataSource;
        this.transactions = transactions;
    }
}
