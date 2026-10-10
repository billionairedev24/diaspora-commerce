package com.afrotransact.taxreturns.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.taxreturns.adapter.in.web.ReturnDtos.CreateReturn;
import com.afrotransact.taxreturns.adapter.in.web.ReturnDtos.Decision;
import com.afrotransact.taxreturns.adapter.in.web.ReturnDtos.EligibilityResponse;
import com.afrotransact.taxreturns.adapter.in.web.ReturnDtos.Inspection;
import com.afrotransact.taxreturns.adapter.in.web.ReturnDtos.Pickup;
import com.afrotransact.taxreturns.adapter.in.web.ReturnDtos.PolicyDto;
import com.afrotransact.taxreturns.adapter.in.web.ReturnDtos.RefundQuoteResponse;
import com.afrotransact.taxreturns.adapter.in.web.ReturnDtos.RmaResponse;
import com.afrotransact.taxreturns.application.InspectCommand;
import com.afrotransact.taxreturns.application.RequestReturnCommand;
import com.afrotransact.taxreturns.application.ReturnPolicyService;
import com.afrotransact.taxreturns.application.ReturnSearch;
import com.afrotransact.taxreturns.application.ReturnService;
import com.afrotransact.taxreturns.domain.returns.ReturnLineRequest;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Returns", description = "Start a return on delivered items, RMA lifecycle, inspection and seller policies")
@RestController
class ReturnController {

    private final ReturnService returns;
    private final ReturnPolicyService policies;
    private final ActorResolver actors;

    ReturnController(ReturnService returns, ReturnPolicyService policies, ActorResolver actors) {
        this.returns = returns;
        this.policies = policies;
        this.actors = actors;
    }

    @Operation(summary = "What can be returned from an order (per shipment and line: window, methods, report a problem)")
    @GetMapping("/v1/returns/eligibility")
    EligibilityResponse eligibility(@RequestParam UUID orderId) {
        return EligibilityResponse.of(returns.eligibility(orderId));
    }

    @Operation(summary = "Start a return (Idempotency-Key required; same key replays the same RMA)",
            description = "Auto-approved returns come back with the label / QR (drop-off), the handover code (driver "
                    + "pickup) or the refund (no return). Seller-fulfilled returns wait for the seller until the SLA.")
    @PostMapping("/v1/returns")
    @ResponseStatus(HttpStatus.CREATED)
    RmaResponse create(@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody CreateReturn body) {
        List<ReturnLineRequest> lines = body.lines().stream().map(l -> new ReturnLineRequest(l.lineId(), l.quantity(),
                l.reason(), l.comment(), l.evidenceAssetIds())).toList();
        return view(returns.request(new RequestReturnCommand(body.orderId(), body.shipmentId(), body.method(), lines,
                body.returnFrom() == null ? null : body.returnFrom().toDomain()), idempotencyKey));
    }

    @Operation(summary = "Returns: customers see their own, sellers their organisation's, staff filter by seller")
    @GetMapping("/v1/returns")
    List<RmaResponse> search(@RequestParam(required = false) UUID orderId,
                             @RequestParam(required = false) RmaStatus status,
                             @RequestParam(required = false) String sellerOrgId,
                             @RequestParam(defaultValue = "50") int limit) {
        return returns.search(new ReturnSearch(sellerOrgId, orderId, status, limit)).stream().map(this::view).toList();
    }

    @Operation(summary = "A return")
    @GetMapping("/v1/returns/{id}")
    RmaResponse get(@PathVariable UUID id) {
        return view(returns.get(id));
    }

    @Operation(summary = "A return by its number (RMA-XXXXXXXX), e.g. scanned from the QR code")
    @GetMapping("/v1/returns/number/{number}")
    RmaResponse byNumber(@PathVariable String number) {
        return view(returns.getByNumber(number));
    }

    @Operation(summary = "Cancel a return before drop-off / pickup (customer)")
    @PostMapping("/v1/returns/{id}/cancel")
    RmaResponse cancel(@PathVariable UUID id) {
        return view(returns.cancel(id));
    }

    @Operation(summary = "Approve (seller for seller-fulfilled, staff otherwise; returns:manage)")
    @PostMapping("/v1/returns/{id}/approve")
    RmaResponse approve(@PathVariable UUID id, @Valid @RequestBody(required = false) Decision body) {
        return view(returns.approve(id, body == null ? null : body.note()));
    }

    @Operation(summary = "Reject with a note to the customer (returns:manage)")
    @PostMapping("/v1/returns/{id}/reject")
    RmaResponse reject(@PathVariable UUID id, @Valid @RequestBody Decision body) {
        return view(returns.reject(id, body.note()));
    }

    @Operation(summary = "Retry the return label / handover code of an approved return (staff, returns:manage)")
    @PostMapping("/v1/returns/{id}/label")
    RmaResponse label(@PathVariable UUID id) {
        return view(returns.issueLabel(id));
    }

    @Operation(summary = "Driver collected the parcel with the customer's handover code (delivery:operate)")
    @PostMapping("/v1/returns/{id}/pickup")
    RmaResponse pickup(@PathVariable UUID id, @Valid @RequestBody(required = false) Pickup body) {
        return view(returns.pickedUp(id, body == null ? null : body.handoverCode()));
    }

    @Operation(summary = "Parcel received at the seller or return centre (returns:manage)")
    @PostMapping("/v1/returns/{id}/receive")
    RmaResponse receive(@PathVariable UUID id) {
        return view(returns.receive(id));
    }

    @Operation(summary = "Inspection per line: restock or damaged; passed lines are refunded (returns:manage)",
            description = "AfroTransact-fulfilled lines post RETURN_RESTOCK / RETURN_DAMAGED to inventory.")
    @PostMapping("/v1/returns/{id}/inspect")
    RmaResponse inspect(@PathVariable UUID id, @Valid @RequestBody Inspection body) {
        return view(returns.inspect(id, new InspectCommand(body.lines().stream()
                .map(l -> new InspectCommand.Line(l.lineId(), l.outcome(), l.condition(), l.note())).toList(),
                body.note())));
    }

    @Operation(summary = "Refund estimate of the return (items + proportional tax + fault shipping − restocking fee)")
    @GetMapping("/v1/returns/{id}/refund-quote")
    RefundQuoteResponse refundQuote(@PathVariable UUID id) {
        return RefundQuoteResponse.of(returns.refundQuote(id));
    }

    @Operation(summary = "A seller's return policy (returns:manage for the organisation, or staff)")
    @GetMapping("/v1/return-policies/{orgId}")
    PolicyDto policy(@PathVariable String orgId) {
        return PolicyDto.of(policies.get(orgId));
    }

    @Operation(summary = "Set the seller's return policy: window ≥ 14 days, restocking fee ≤ 20 %, return address")
    @PutMapping("/v1/return-policies/{orgId}")
    PolicyDto putPolicy(@PathVariable String orgId, @Valid @RequestBody PolicyDto body) {
        return PolicyDto.of(policies.put(body.toDomain(orgId)));
    }

    private RmaResponse view(Rma r) {
        PrincipalType type = actors.current().type();
        return RmaResponse.of(r, type == PrincipalType.CUSTOMER || type == PrincipalType.STAFF);
    }
}
