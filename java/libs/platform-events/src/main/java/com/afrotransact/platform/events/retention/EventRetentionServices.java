package com.afrotransact.platform.events.retention;

import java.time.Clock;
import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds the JDBC retention service for one database. The auto-configuration does this for the single database of a
 * standalone service; an application that hosts several modules (each with its own database) calls this once per module.
 */
public final class EventRetentionServices {

    private EventRetentionServices() {
    }

    public static EventRetentionService jdbc(JdbcClient jdbc, DataSource dataSource, EventRetentionProperties properties,
                                             Clock clock, MeterRegistry meters) {
        return new JdbcEventRetentionService(jdbc, new PostgresAdvisoryRetentionLock(dataSource), properties, clock,
                meters);
    }
}
