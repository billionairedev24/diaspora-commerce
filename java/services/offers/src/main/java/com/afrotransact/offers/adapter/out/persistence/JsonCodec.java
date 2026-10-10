package com.afrotransact.offers.adapter.out.persistence;

import java.util.List;

import com.afrotransact.offers.domain.BuyBoxReason;
import com.afrotransact.offers.domain.LineDiscount;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.port.BuyBoxRepository.AuditedOffer;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for value objects stored as documents. */
@Component
class JsonCodec {

    private static final TypeReference<List<BuyBoxReason>> REASONS = new TypeReference<>() { };
    private static final TypeReference<List<AuditedOffer>> AUDITED = new TypeReference<>() { };
    private static final TypeReference<List<LineDiscount>> LINES = new TypeReference<>() { };

    private final JsonMapper mapper;

    JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    ShipFrom shipFrom(String json) {
        return json == null ? null : mapper.readValue(json, ShipFrom.class);
    }

    List<BuyBoxReason> reasons(String json) {
        return json == null ? List.of() : mapper.readValue(json, REASONS);
    }

    List<AuditedOffer> audited(String json) {
        return json == null ? List.of() : mapper.readValue(json, AUDITED);
    }

    List<LineDiscount> lines(String json) {
        return json == null ? List.of() : mapper.readValue(json, LINES);
    }
}
