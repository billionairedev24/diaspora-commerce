package com.afrotransact.offers.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.offers.adapter.in.web.OfferController.OfferPresenceResponse;
import com.afrotransact.offers.adapter.in.web.OfferDtos.CreateOfferRequest;
import com.afrotransact.offers.adapter.in.web.OfferDtos.OfferResponse;
import com.afrotransact.offers.adapter.in.web.OfferDtos.UpdateOfferRequest;
import com.afrotransact.offers.adapter.in.web.OfferSettingsController.PolicyDto;
import com.afrotransact.offers.application.CreateOfferCommand;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferPackage;
import com.afrotransact.offers.domain.OfferPolicy;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.StockStatus;
import com.afrotransact.offers.testsupport.OpenApiContract;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The offers contract (contracts/openapi/offers.yaml) for S383 and S384: the routes, the TAX_HELD status and its reason, the
 * condition note and package on the offer, and the way stray fields reach the service (page content is refused there).
 */
class OfferContractTest {

    private static final OpenApiContract CONTRACT = OpenApiContract.of("offers");
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @Test
    void everyRouteTheNewEndpointsServeIsInTheContract() {
        assertThat(CONTRACT.routes()).contains("GET /v1/offers/presence", "GET /v1/offers/settings",
                "PUT /v1/offers/settings", "POST /v1/offers");
        assertThat(OpenApiContract.routesOf(OfferController.class, OfferSettingsController.class))
                .contains("GET /v1/offers/presence", "GET /v1/offers/settings", "PUT /v1/offers/settings");
    }

    @Test
    void theOfferStatusesOfTheContractIncludeTaxHeld() {
        assertThat(CONTRACT.properties("Offer")).contains("status", "heldReason", "conditionNote", "packaging");
        Set<String> statuses = new TreeSet<>();
        for (OfferStatus s : OfferStatus.values()) {
            statuses.add(s.name());
        }
        assertThat(statuses).contains("TAX_HELD");
    }

    @Test
    void theOfferResponseCarriesExactlyWhatTheContractDescribes() {
        Offer held = new Offer(UUID.randomUUID(), "AT00000002", "AT00000001", UUID.randomUUID(), "4006381333931",
                UUID.randomUUID(), "org_b", SellerKind.SELLER, "ANK-1", "ANK-1", OfferCondition.USED_GOOD, 999, 1299L,
                "USD", 0, Fulfilment.AFROTRANSACT, 0, 2, null, OfferStatus.ACTIVE, true, NOW, NOW, 3,
                "A small scratch", new OfferPackage(12, 8, 4, 2.5), Map.of("sold_heated", false), null,
                Offer.TAX_BASIS_SELLER_DECISION).heldForTax(Offer.HOLD_DECISION_MISSING, NOW);
        OfferView view = new OfferView(held, "Lagos Textile Hub", 0, StockStatus.of(0, 5), 4, false);

        JsonNode json = JSON.valueToTree(OfferResponse.of(view));

        assertThat(OpenApiContract.keys(json)).isSubsetOf(CONTRACT.properties("Offer"));
        assertThat(OpenApiContract.keys(json)).containsAll(CONTRACT.required("Offer"));
        assertThat(json.get("status").asString()).isEqualTo("TAX_HELD");
        assertThat(json.get("heldReason").asString()).isEqualTo("TAX_DECISION_MISSING");
        assertThat(json.get("conditionNote").asString()).isEqualTo("A small scratch");
        assertThat(OpenApiContract.keys(json.get("packaging"))).isSubsetOf(CONTRACT.properties("OfferPackage"));
    }

    @Test
    void theCreateAndUpdateRequestsAcceptEveryFieldOfTheirSchemas() {
        for (var entry : Map.of("CreateOfferRequest", CreateOfferRequest.class, "UpdateOfferRequest",
                UpdateOfferRequest.class).entrySet()) {
            Set<String> accepted = new TreeSet<>();
            for (var component : entry.getValue().getRecordComponents()) {
                accepted.add(component.getName());
            }
            assertThat(accepted).as("contract fields of %s the request record does not accept", entry.getKey())
                    .containsAll(CONTRACT.properties(entry.getKey()));
        }
    }

    @Test
    void thePresenceAndSettingsCarryExactlyWhatTheContractDescribes() {
        JsonNode presence = JSON.valueToTree(new OfferPresenceResponse("AT00000001", "org_b", true));
        assertThat(OpenApiContract.keys(presence)).isSubsetOf(CONTRACT.properties("OfferPresence"));
        assertThat(OpenApiContract.keys(presence)).containsAll(CONTRACT.required("OfferPresence"));

        JsonNode settings = JSON.valueToTree(PolicyDto.of(new OfferPolicy(200, 108, 150, 200, "staff", NOW, 2)));
        assertThat(OpenApiContract.keys(settings)).isSubsetOf(CONTRACT.properties("OfferPolicySettings"));
        assertThat(OpenApiContract.keys(settings)).containsAll(CONTRACT.required("OfferPolicySettings"));
    }

    @Test
    void fieldsAnOfferDoesNotHaveReachTheServiceAndAreRefusedThereNotSilentlyDropped() {
        CreateOfferRequest request = JSON.readValue("""
                {"atin":"AT00000002","sellerSku":"ANK-1","fulfilment":"SELLER","priceMinor":1299,"quantity":3,
                 "shipFrom":{"country":"US"},"title":"A better title","images":["https://x/a.jpg"],
                 "attributes":{"material":"silk"},"brand":"Other","categoryId":"c1"}
                """, CreateOfferRequest.class);

        CreateOfferCommand command = request.toCommand();

        assertThat(command.sellerSku()).isEqualTo("ANK-1");
        assertThat(command.unknownFields()).containsExactlyInAnyOrder("title", "images", "attributes", "brand",
                "categoryId");
    }

    @Test
    void aRequestWithOnlyOfferTermsCarriesNoStrayFields() {
        CreateOfferRequest request = JSON.readValue("""
                {"atin":"AT00000002","sellerSku":"ANK-1","fulfilment":"AFROTRANSACT","priceMinor":1299,
                 "condition":"USED_GOOD","conditionNote":"A scratch",
                 "packaging":{"lengthIn":12,"widthIn":8,"heightIn":4,"weightLb":2.5},
                 "sellerBehaviour":{"sold_heated":false}}
                """, CreateOfferRequest.class);

        CreateOfferCommand command = request.toCommand();

        assertThat(command.unknownFields()).isEmpty();
        assertThat(command.conditionNote()).isEqualTo("A scratch");
        assertThat(command.packaging()).isEqualTo(new OfferPackage(12, 8, 4, 2.5));
        assertThat(command.sellerBehaviour()).containsEntry("sold_heated", false);
    }
}
