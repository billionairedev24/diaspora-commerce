package com.afrotransact.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.ImportDtos.ImportResponse;
import com.afrotransact.catalog.adapter.in.web.ImportDtos.RowResponse;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ImportStatus;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.testsupport.OpenApiContract;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S393 and S394 against contracts/openapi/catalog.yaml: the upload modes OFFER and PRICE_QTY, the row outcomes OFFER_CREATED
 * and HELD, the file hash and the ATIN a row ended on.
 */
class ImportContractTest {

    private static final OpenApiContract CONTRACT = OpenApiContract.of("catalog");
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private static Set<String> names(Enum<?>[] values) {
        Set<String> out = new TreeSet<>();
        for (Enum<?> e : values) {
            out.add(e.name());
        }
        return out;
    }

    @Test
    void theContractListsEveryUploadModeAndRowOutcomeTheCodeHas() {
        assertThat(CONTRACT.routes()).contains("GET /v1/imports/template", "POST /v1/imports", "GET /v1/imports/{id}/report");
        assertThat(OpenApiContract.routesOf(ImportController.class)).contains("GET /v1/imports/template", "POST /v1/imports");
        assertThat(names(ImportMode.values())).containsExactlyInAnyOrder("CREATE", "UPDATE", "OFFER", "PRICE_QTY");
        assertThat(names(RowOutcome.values())).contains("OFFER_CREATED", "HELD");
    }

    @Test
    void theImportResponseCarriesExactlyWhatTheContractDescribes() {
        ProductImport u = new ProductImport(UUID.randomUUID(), "BU-000001", new ListingOwner(ListingOwnerKind.SELLER,
                "org_b"), "user_b", "Ada", "offers.csv", SpreadsheetFormat.CSV, ImportMode.OFFER, null, List.of("Barcode"),
                List.of(), ImportStatus.COMPLETED, false, 3, 2, 1, 0, 3, 3, 0, 1, 0, false, NOW, NOW, NOW, NOW, "abc",
                UUID.randomUUID(), 1, 1, 4);

        JsonNode json = JSON.valueToTree(ImportResponse.of(u));

        assertThat(OpenApiContract.keys(json)).isSubsetOf(CONTRACT.properties("ProductImport"));
        assertThat(CONTRACT.properties("ProductImport")).contains("fileSha256", "sameFileAs", "offersCreated", "heldRows");
        assertThat(json.get("mode").asString()).isEqualTo("OFFER");
        assertThat(json.get("offersCreated").asInt()).isEqualTo(1);
        assertThat(json.get("heldRows").asInt()).isEqualTo(1);
        assertThat(CONTRACT.enumValues("ProductImportRow")).isEmpty();
    }

    @Test
    void theRowResponseCarriesTheAtinAndTheNewOutcomes() {
        StoredRow held = new StoredRow(2, "r2", "AT00000002", RowStatus.WARNING, List.of(), List.of("4006381333931"),
                RowOutcome.HELD, "We are checking the tax details for this item.", null, "AT00000002", UUID.randomUUID());

        JsonNode json = JSON.valueToTree(RowResponse.of(held, List.of()));

        assertThat(OpenApiContract.keys(json)).isSubsetOf(CONTRACT.properties("ProductImportRow"));
        assertThat(json.get("outcome").asString()).isEqualTo("HELD");
        assertThat(json.get("atin").asString()).isEqualTo("AT00000002");
        assertThat(CONTRACT.properties("ProductImportRow")).contains("atin", "offerId");
    }
}
