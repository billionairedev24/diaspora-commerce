package com.afrotransact.platform.test;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared, reusable PostgreSQL 17 + PostGIS container for adapter and integration tests. */
public final class PostgresTestContainer {

    /** Multi-arch (amd64 + arm64) PostGIS build by the PostGIS image maintainer; postgis/postgis is amd64 only. */
    public static final DockerImageName IMAGE =
            DockerImageName.parse("imresamu/postgis:17-3.5.4-alpine3.22").asCompatibleSubstituteFor("postgres");

    private PostgresTestContainer() {
    }

    public static PostgreSQLContainer create() {
        return new PostgreSQLContainer(IMAGE).withReuse(true);
    }
}
