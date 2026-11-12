package com.afrotransact.offers.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.offers.adapter.in.web.OfferBatchController.BatchResponse;
import com.afrotransact.offers.adapter.in.web.OfferBatchController.PriceStockBatchRequest;
import com.afrotransact.offers.adapter.in.web.OfferBatchController.PriceStockRowDto;
import com.afrotransact.offers.adapter.in.web.OfferBatchController.UpsertBatchRequest;
import com.afrotransact.offers.adapter.in.web.OfferBatchController.UpsertOfferRowDto;
import com.afrotransact.offers.application.OfferBatchService.Outcome;
import com.afrotransact.offers.application.OfferBatchService.RowResult;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.testsupport.OpenApiContract;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S393 and S394: the batch offer endpoints match contracts/openapi/offers.yaml (routes, request and response fields). */
class OfferBatchContractTest {

    private static final OpenApiContract CONTRACT = OpenApiContract.of("offers");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theBatchRoutesAreInTheContractAndServedByTheController() {
        assertThat(CONTRACT.routes()).contains("POST /v1/offers/batch", "PUT /v1/offers/prices-and-stock");
        assertThat(OpenApiContract.routesOf(OfferBatchController.class)).contains("POST /v1/offers/batch",
                "PUT /v1/offers/prices-and-stock");
    }

    @Test
    void theRequestRecordsAcceptEveryFieldOfTheirSchemas() {
        assertThat(fields(UpsertOfferRowDto.class)).containsAll(CONTRACT.properties("UpsertOfferRow"));
        assertThat(fields(PriceStockRowDto.class)).containsAll(CONTRACT.properties("PriceStockRow"));
        assertThat(fields(UpsertBatchRequest.class)).containsAll(CONTRACT.properties("UpsertOffersRequest"));
        assertThat(fields(PriceStockBatchRequest.class)).containsAll(CONTRACT.properties("PricesAndStockRequest"));
    }

    @Test
    void theResponseCarriesExactlyWhatTheContractDescribes() {
        BatchResponse response = BatchResponse.of(List.of(
                new RowResult("2", Outcome.HELD, UUID.randomUUID(), "AT00000002", OfferStatus.TAX_HELD,
                        "TAX_DECISION_MISSING", "We are checking the tax details for this item."),
                new RowResult("3", Outcome.FAILED, null, null, null, "offers.sku-not-found", "No.")));

        JsonNode json = JSON.valueToTree(response);

        assertThat(OpenApiContract.keys(json)).isSubsetOf(CONTRACT.properties("OfferBatchResponse"));
        assertThat(OpenApiContract.keys(json.get("results").get(0)))
                .isSubsetOf(CONTRACT.properties("OfferBatchRowResult"));
        assertThat(OpenApiContract.keys(json.get("results").get(0))).containsAll(CONTRACT.required("OfferBatchRowResult"));
        assertThat(json.get("held").asInt()).isEqualTo(1);
        assertThat(json.get("failed").asInt()).isEqualTo(1);
        Set<String> outcomes = new TreeSet<>();
        for (Outcome o : Outcome.values()) {
            outcomes.add(o.name());
        }
        assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "UPDATED", "UNCHANGED", "HELD", "FAILED");
    }

    private static Set<String> fields(Class<? extends Record> type) {
        Set<String> out = new TreeSet<>();
        for (var component : type.getRecordComponents()) {
            out.add(component.getName());
        }
        return out;
    }
}
