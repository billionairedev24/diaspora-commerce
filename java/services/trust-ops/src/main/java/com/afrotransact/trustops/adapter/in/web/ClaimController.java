package com.afrotransact.trustops.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.trustops.adapter.in.web.ClaimDtos.ClaimResponse;
import com.afrotransact.trustops.adapter.in.web.ClaimDtos.DecisionRequest;
import com.afrotransact.trustops.adapter.in.web.ClaimDtos.EscalateClaimRequest;
import com.afrotransact.trustops.adapter.in.web.ClaimDtos.EvidenceRequest;
import com.afrotransact.trustops.adapter.in.web.ClaimDtos.HistoryResponse;
import com.afrotransact.trustops.adapter.in.web.ClaimDtos.OpenClaimRequest;
import com.afrotransact.trustops.adapter.in.web.ClaimDtos.SellerResponseRequest;
import com.afrotransact.trustops.adapter.in.web.CommonDtos.PageResponse;
import com.afrotransact.trustops.application.ClaimService;
import com.afrotransact.trustops.application.DecideClaimCommand;
import com.afrotransact.trustops.application.OpenClaimCommand;
import com.afrotransact.trustops.domain.ClaimStatus;
import com.afrotransact.trustops.port.PaymentsClient.RefundLine;
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

@Tag(name = "Disputes & A-to-z claims", description = "Customers open claims on orders or shipments, sellers respond "
        + "within the response window, staff decide with a refund (payments) or a denial")
@RestController
class ClaimController {

    private final ClaimService claims;

    ClaimController(ClaimService claims) {
        this.claims = claims;
    }

    @Operation(summary = "Open a claim (customer; staff with disputes:manage on a customer's behalf)")
    @PostMapping("/v1/claims")
    @ResponseStatus(HttpStatus.CREATED)
    ClaimResponse open(@Valid @RequestBody OpenClaimRequest b) {
        return ClaimResponse.of(claims.open(new OpenClaimCommand(b.type(), b.orderId(), b.orderNumber(),
                b.shipmentId(), b.sellerOrgId(), b.customerId(), b.amountMinor(), b.currency(), b.description(),
                b.evidenceAssetIds())));
    }

    @Operation(summary = "My claims (customer)")
    @GetMapping("/v1/claims/mine")
    PageResponse<ClaimResponse> mine(@RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return PageResponse.of(claims.mine(CommonDtos.page(page), CommonDtos.size(size)), ClaimResponse::of);
    }

    @Operation(summary = "Claims against a seller organisation (its members with support:respond, or staff)")
    @GetMapping("/v1/claims/seller/{orgId}")
    PageResponse<ClaimResponse> forSeller(@PathVariable String orgId,
                                          @RequestParam(required = false) ClaimStatus status,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return PageResponse.of(claims.forSeller(orgId, status, CommonDtos.page(page), CommonDtos.size(size)),
                ClaimResponse::of);
    }

    @Operation(summary = "Staff queue (disputes:manage), soonest due first; default open claims")
    @GetMapping("/v1/claims")
    PageResponse<ClaimResponse> queue(@RequestParam(required = false) ClaimStatus status,
                                      @RequestParam(required = false) String sellerOrgId,
                                      @RequestParam(required = false) String orderId,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return PageResponse.of(claims.queue(status, sellerOrgId, orderId, CommonDtos.page(page),
                CommonDtos.size(size)), ClaimResponse::of);
    }

    @Operation(summary = "One claim (customer, seller or staff)")
    @GetMapping("/v1/claims/{id}")
    ClaimResponse get(@PathVariable UUID id) {
        return ClaimResponse.of(claims.get(id));
    }

    @Operation(summary = "Claim timeline")
    @GetMapping("/v1/claims/{id}/history")
    List<HistoryResponse> history(@PathVariable UUID id) {
        return claims.history(id).stream().map(HistoryResponse::of).toList();
    }

    @Operation(summary = "Add evidence (media asset id) while the claim is open")
    @PostMapping("/v1/claims/{id}/evidence")
    ClaimResponse evidence(@PathVariable UUID id, @Valid @RequestBody EvidenceRequest b) {
        return ClaimResponse.of(claims.addEvidence(id, b.assetId(), b.note()));
    }

    @Operation(summary = "Seller response within the response window (support:respond for the seller org)")
    @PostMapping("/v1/claims/{id}/seller-response")
    ClaimResponse sellerResponse(@PathVariable UUID id, @Valid @RequestBody SellerResponseRequest b) {
        return ClaimResponse.of(claims.sellerRespond(id, b.message(), b.evidenceAssetIds()));
    }

    @Operation(summary = "Escalate (disputes:manage)")
    @PostMapping("/v1/claims/{id}/escalate")
    ClaimResponse escalate(@PathVariable UUID id, @Valid @RequestBody EscalateClaimRequest b) {
        return ClaimResponse.of(claims.escalate(id, b.reason()));
    }

    @Operation(summary = "Withdraw (the customer)")
    @PostMapping("/v1/claims/{id}/withdraw")
    ClaimResponse withdraw(@PathVariable UUID id) {
        return ClaimResponse.of(claims.withdraw(id));
    }

    @Operation(summary = "Decide: refund through payments or deny (disputes:manage); notifies both parties")
    @PostMapping("/v1/claims/{id}/decision")
    ClaimResponse decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest b) {
        List<RefundLine> lines = b.refundLines() == null ? List.of() : b.refundLines().stream()
                .map(l -> new RefundLine(l.lineId(), l.itemsMinor(), l.shippingMinor(), l.taxMinor())).toList();
        return ClaimResponse.of(claims.decide(id, new DecideClaimCommand(b.outcome(), b.note(), b.paymentId(),
                lines, b.refundAmountMinor())));
    }
}
