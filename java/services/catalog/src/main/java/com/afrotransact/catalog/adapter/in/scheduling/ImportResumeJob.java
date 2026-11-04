package com.afrotransact.catalog.adapter.in.scheduling;

import com.afrotransact.catalog.application.ProductImportService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Picks up bulk uploads a crashed or restarted pod left queued or running (S308). */
@Component
class ImportResumeJob {

    private final ProductImportService imports;

    ImportResumeJob(ProductImportService imports) {
        this.imports = imports;
    }

    @Scheduled(fixedDelayString = "${at.catalog.imports.resume-interval:60s}",
            initialDelayString = "${at.catalog.imports.resume-interval:60s}")
    void resume() {
        imports.resumeStalled();
    }
}
