package com.afrotransact.catalog.adapter.in.startup;

import com.afrotransact.catalog.application.TaxonomyImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The seed path of the category tax tree: with {@code at.catalog.taxonomy.import-on-start=true} (local and dev) the
 * bundled dataset is imported when the service starts, so a dataset change reaches a running stack with a restart.
 * Idempotent; production changes go through the staff import (POST /v1/taxonomy/import) with a reason.
 */
@Component
@ConditionalOnProperty(name = "at.catalog.taxonomy.import-on-start", havingValue = "true")
class TaxonomyImportRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TaxonomyImportRunner.class);

    private final TaxonomyImportService imports;

    TaxonomyImportRunner(TaxonomyImportService imports) {
        this.imports = imports;
    }

    @Override
    public void run(ApplicationArguments args) {
        TaxonomyImportService.Report r = imports.runAsSystem(false, "Import of the category tax tree at start-up");
        LOG.info("Category tax tree imported ({}): {} nodes created, {} updated, {} product types created, {} updated",
                r.ruleVersion(), r.createdNodes(), r.updatedNodes(), r.createdTypes(), r.updatedTypes());
    }
}
