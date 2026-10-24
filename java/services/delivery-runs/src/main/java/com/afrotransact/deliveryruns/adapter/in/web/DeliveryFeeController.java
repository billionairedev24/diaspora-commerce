package com.afrotransact.deliveryruns.adapter.in.web;

import com.afrotransact.deliveryruns.adapter.in.web.DeliveryFeeDtos.CarrierRequest;
import com.afrotransact.deliveryruns.adapter.in.web.DeliveryFeeDtos.ChangesResponse;
import com.afrotransact.deliveryruns.adapter.in.web.DeliveryFeeDtos.PreviewRequest;
import com.afrotransact.deliveryruns.adapter.in.web.DeliveryFeeDtos.ResolutionResponse;
import com.afrotransact.deliveryruns.adapter.in.web.DeliveryFeeDtos.RuleRequest;
import com.afrotransact.deliveryruns.adapter.in.web.DeliveryFeeDtos.RuleResponse;
import com.afrotransact.deliveryruns.adapter.in.web.DeliveryFeeDtos.RulesResponse;
import com.afrotransact.deliveryruns.application.DeliveryFeeService;
import com.afrotransact.deliveryruns.domain.DeliveryFeeRule;
import com.afrotransact.deliveryruns.domain.FeeMethod;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Delivery fees", description = "Staff: the delivery fee by delivery method (AfroTransact delivery by "
        + "distance band, carrier shipping, pickup) and its audit trail (delivery-runs:read to view; "
        + "preorders:manage to change)")
@RestController
class DeliveryFeeController {

    private final DeliveryFeeService fees;

    DeliveryFeeController(DeliveryFeeService fees) {
        this.fees = fees;
    }

    @Operation(summary = "Every delivery fee rule (delivery-runs:read)",
            description = "One rule per method and currency: AFROTRANSACT_DELIVERY (flat fee by distance band, inside "
                    + "covered locations), CARRIER (carrier-gateway quote plus handling, beyond coverage) and "
                    + "PICKUP (often 0).")
    @GetMapping("/v1/delivery-fees")
    RulesResponse rules() {
        return RulesResponse.of(fees.rules());
    }

    @Operation(summary = "Set the delivery fee of one method and currency (preorders:manage)",
            description = "Creates the rule or makes its next version. A reason is required (422 "
                    + "delivery-runs.reason-required) and is logged with who and when; numbers that are not a valid "
                    + "rule are 422 (bands must start at 0 and not overlap or leave a gap, fees are 0 to 100.00). "
                    + "If-Match is the version seen (0 when the rule does not exist yet; 412 "
                    + "delivery-runs.version-mismatch when it changed). A save that changes nothing makes no new "
                    + "version. Orders already placed keep the rule version they were charged by.")
    @PutMapping("/v1/delivery-fees/{method}/{currency}")
    ResponseEntity<RuleResponse> replace(@PathVariable FeeMethod method, @PathVariable String currency,
                                         @Valid @RequestBody RuleRequest body,
                                         @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                         String ifMatch) {
        DeliveryFeeRule saved = fees.replace(method, currency, body.toCommand(), Versions.expected(ifMatch));
        return Versions.tagged(RuleResponse.of(saved), saved.version());
    }

    @Operation(summary = "Audit trail of the delivery fees, newest first (delivery-runs:read)",
            description = "Every version of every rule (or one method and currency) with the rule before and after, "
                    + "who changed it, when and why.")
    @GetMapping("/v1/delivery-fees/changes")
    ChangesResponse changes(@RequestParam(required = false) FeeMethod method,
                            @RequestParam(required = false) String currency,
                            @RequestParam(defaultValue = "50") int limit) {
        return ChangesResponse.of(fees.changes(method, currency, limit));
    }

    @Operation(summary = "What would a customer pay? (delivery-runs:read)",
            description = "The same resolution checkout runs: the window's own fee first, then the method's rule. "
                    + "applies is false when no rule can price it (no rule, or beyond how far AfroTransact "
                    + "delivers: the order ships by carrier).")
    @PostMapping("/v1/delivery-fees/preview")
    ResolutionResponse preview(@Valid @RequestBody PreviewRequest body) {
        return ResolutionResponse.of(fees.preview(body.toCommand()));
    }

    @Operation(summary = "The carrier rate of an order shipped beyond coverage (delivery-runs:reserve, orders-service)",
            description = "The carrier-gateway quote plus the rule's handling fee; the rule's fallback fee when there "
                    + "is no quote; 0 over the free-shipping threshold. applies is false without a CARRIER rule for "
                    + "the currency (orders-service then uses its own flat rates).")
    @PostMapping("/v1/service/delivery-fees/carrier")
    ResolutionResponse carrier(@Valid @RequestBody CarrierRequest body) {
        return ResolutionResponse.of(fees.carrier(body.currency(), body.carrierQuoteMinor(), body.itemsMinor()));
    }
}
