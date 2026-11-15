package com.afrotransact.module.core.fixture.shared;

import java.util.List;
import javax.sql.DataSource;

import org.springframework.transaction.PlatformTransactionManager;

/** Fixture: framework or platform code, which belongs to no module. */
public class PlatformCode {

    public final List<DataSource> dataSources;
    public final PlatformTransactionManager transactions;

    public PlatformCode(List<DataSource> dataSources, PlatformTransactionManager transactions) {
        this.dataSources = dataSources;
        this.transactions = transactions;
    }
}
