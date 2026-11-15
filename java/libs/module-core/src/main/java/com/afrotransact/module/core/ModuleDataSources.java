package com.afrotransact.module.core;

import java.time.Duration;

import com.zaxxer.hikari.HikariDataSource;

/** Builds a module's own connection pool: sized per module so that no module can take the whole database's connections. */
final class ModuleDataSources {

    private ModuleDataSources() {
    }

    static HikariDataSource create(ModuleDefinition module, ModuleProperties.Database db) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName(module.name());
        dataSource.setJdbcUrl(db.url());
        dataSource.setUsername(db.username());
        dataSource.setPassword(db.password());
        dataSource.setMaximumPoolSize(db.maximumPoolSize());
        dataSource.setMinimumIdle(Math.min(db.minimumIdle(), db.maximumPoolSize()));
        dataSource.setConnectionTimeout(millis(db.connectionTimeout()));
        dataSource.setIdleTimeout(millis(db.idleTimeout()));
        dataSource.setRegisterMbeans(false);
        return dataSource;
    }

    /** The database name of a JDBC URL, without host, credentials or parameters: what an operator recognises. */
    static String databaseName(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl;
        int query = url.indexOf('?');
        String bare = query < 0 ? url : url.substring(0, query);
        return bare.substring(bare.lastIndexOf('/') + 1);
    }

    private static long millis(Duration duration) {
        return duration.toMillis();
    }
}
