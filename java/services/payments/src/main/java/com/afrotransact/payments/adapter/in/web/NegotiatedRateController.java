package com.afrotransact.payments.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.EndNegotiatedRateRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.NegotiatedRateRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.NegotiatedRateResponse;
import com.afrotransact.payments.application.NegotiatedRateService;
import com.afrotransact.payments.domain.NegotiatedRateStatus;
import com.afrotransact.payments.domain.NegotiatedRateType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Negotiated rates", description = "Commission and connection fees negotiated with one organisation: "
        + "dated, with a required reason, audited and expired by a job; they win over plan, category and default "
        + "rates while active")
@RestController
class NegotiatedRateController {

    private final NegotiatedRateService rates;

    NegotiatedRateController(NegotiatedRateService rates) {
        this.rates = rates;
    }

    @Operation(summary = "Negotiated rates, newest first",
            description = "Staff fee readers (payments:read) and services (payments:fees.read) see every "
                    + "organisation (filter with orgId); organisation members only their own. Filters: type "
                    + "(COMMISSION, CONNECTION_FEE), status (SCHEDULED, ACTIVE, ENDED, EXPIRED); limit ≤ 500.")
    @GetMapping("/v1/negotiated-rates")
    List<NegotiatedRateResponse> listNegotiatedRates(@RequestParam(required = false) String orgId,
                                                     @RequestParam(required = false) NegotiatedRateType type,
                                                     @RequestParam(required = false) NegotiatedRateStatus status,
                                                     @RequestParam(defaultValue = "200") int limit) {
        return rates.list(orgId, type, status, limit).stream().map(NegotiatedRateResponse::of).toList();
    }

    @Operation(summary = "Negotiate a rate with an organisation (Super Admin / Finance, payments:configure)",
            description = "A commission (products) or connection fee (services) for one category or '*', from "
                    + "startsAt (default now, never in the past) to endsAt (optional). The reason is required and "
                    + "audited with negotiatedBy and the reference. 409 payments.negotiated-rate-overlap when another "
                    + "rate of the organisation, type and category overlaps; 422 payments.retail-no-fees for "
                    + "AfroTransact Retail. Payments created before it starts keep their snapshotted rate.")
    @PostMapping("/v1/negotiated-rates")
    @ResponseStatus(HttpStatus.CREATED)
    NegotiatedRateResponse createNegotiatedRate(@Valid @RequestBody NegotiatedRateRequest body) {
        return NegotiatedRateResponse.of(rates.create(body.toCommand()));
    }

    @Operation(summary = "One negotiated rate with its audit trail")
    @GetMapping("/v1/negotiated-rates/{id}")
    NegotiatedRateResponse negotiatedRate(@PathVariable UUID id) {
        return NegotiatedRateResponse.of(rates.get(id));
    }

    @Operation(summary = "End a negotiated rate now (Super Admin / Finance, payments:configure)",
            description = "With a reason (audited). The organisation's plan, category or default rate applies to "
                    + "payments created afterwards. 409 payments.negotiated-rate-ended when it already ended or "
                    + "expired.")
    @PostMapping("/v1/negotiated-rates/{id}/end")
    NegotiatedRateResponse endNegotiatedRate(@PathVariable UUID id,
                                             @Valid @RequestBody EndNegotiatedRateRequest body) {
        return NegotiatedRateResponse.of(rates.end(id, body.reason()));
    }
}
