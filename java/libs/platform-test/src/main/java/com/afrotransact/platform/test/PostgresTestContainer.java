package com.afrotransact.platform.test;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared, reusable PostgreSQL 17 + PostGIS container for adapter and integration tests. */
public final class PostgresTestContainer {

    public static final DockerImageName IMAGE =
            DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres");

    private PostgresTestContainer() {
    }

    public static PostgreSQLContainer create() {
        return new PostgreSQLContainer(IMAGE).withReuse(true);
    }
}
