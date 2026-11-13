package com.afrotransact.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.afrotransact.catalog.application.ImportSettings;
import com.afrotransact.catalog.domain.imports.ImportMode;
import org.junit.jupiter.api.Test;

/** The bulk caps are configuration (at.catalog.imports.*), not constants in the code (S393 to S396). */
class ImportSettingsConfigTest {

    @Test
    void theCapsComeFromConfigurationNotFromCode() {
        ImportSettings configured = new CatalogConfiguration().importSettings(100, Duration.ofMinutes(2), 7, 9, 3);

        assertThat(configured.rowCap(ImportMode.OFFER)).isEqualTo(7);
        assertThat(configured.rowCap(ImportMode.CREATE)).isEqualTo(7);
        assertThat(configured.rowCap(ImportMode.UPDATE)).isEqualTo(7);
        assertThat(configured.rowCap(ImportMode.PRICE_QTY)).isEqualTo(9);
        assertThat(configured.maxUploadsPerDay()).isEqualTo(3);
    }
}
