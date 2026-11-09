package com.afrotransact.catalog.adapter.in.web;

import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.application.TaxonomyImportService;
import com.afrotransact.catalog.application.TaxonomyImportService.Report;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Taxonomy (back office)")
@RestController
class TaxonomyImportController {

    private final TaxonomyImportService imports;

    TaxonomyImportController(TaxonomyImportService imports) {
        this.imports = imports;
    }

    /**
     * @param dataset the JSON of category_tax_map.json; omitted: the dataset bundled with this build
     * @param dryRun  report only
     * @param force   also reset names, placement, status and detail questions to the dataset
     */
    record ImportBody(Map<String, Object> dataset, boolean dryRun, boolean force,
                      @NotBlank @Size(min = 3, max = 1000) String reason) {
    }

    record ImportResponse(String ruleVersion, boolean dryRun, boolean force, int departments, int categories,
                          int productTypes, int createdNodes, int updatedNodes, int unchangedNodes, int createdTypes,
                          int updatedTypes, List<String> notes) {

        static ImportResponse of(Report r) {
            return new ImportResponse(r.ruleVersion(), r.dryRun(), r.force(), r.departments(), r.categories(),
                    r.productTypes(), r.createdNodes(), r.updatedNodes(), r.unchangedNodes(), r.createdTypes(),
                    r.updatedTypes(), r.notes());
        }
    }

    @Operation(summary = "Import the category tax tree (idempotent, audited)",
            description = "Loads departments, categories (with tax profile and status), product types and the tax questions of "
                    + "every category from the category tax dataset. Creates what is missing and refreshes the tax "
                    + "questions; leaves names, placement, statuses and detail questions that staff changed alone unless "
                    + "force is set. dryRun reports without writing. Needs catalog:taxonomy.")
    @PostMapping("/v1/taxonomy/import")
    ImportResponse importTaxonomy(@Valid @RequestBody ImportBody body) {
        return ImportResponse.of(imports.run(body.dataset(), body.dryRun(), body.force(), body.reason()));
    }
}
