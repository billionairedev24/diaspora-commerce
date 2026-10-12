package com.afrotransact.payments.adapter.in.web;

import java.util.List;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PlanFeeReleaseRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PlanFeeWithholdingRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PlanFeeWithholdingResponse;
import com.afrotransact.payments.application.PlanFeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Plan fees from earnings", description = "Seller plan invoices paid from earnings instead of the card "
        + "(S298): withheld from the next transfers, released when the invoice is voided or refunded")
@RestController
class PlanFeeController {

    private final PlanFeeService planFees;

    PlanFeeController(PlanFeeService planFees) {
        this.planFees = planFees;
    }

    @Operation(summary = "Withhold a plan invoice from the organisation's earnings (seller service, "
            + "payments:plan-fees)",
            description = "Takes up to amountMinor from the organisation's delivered, undisputed and untransferred "
                    + "nets (less outstanding label charges and earlier plan fees): status WITHHELD with withheldMinor "
                    + "(the card pays the rest), or DECLINED with a reason when nothing is available. The seller "
                    + "payable is debited at once (Dr 2000:org / Cr 4600) and the next transfers are smaller by it. "
                    + "Idempotency-Key must be plan-fee-<invoiceId>; a replay answers the first decision, another "
                    + "amount for the same invoice is 409 payments.idempotency-conflict.")
    @PostMapping("/v1/orgs/{orgId}/plan-fee-withholdings")
    @ResponseStatus(HttpStatus.CREATED)
    PlanFeeWithholdingResponse withhold(@PathVariable String orgId,
                                        @RequestHeader(value = PaymentController.IDEMPOTENCY_KEY, required = false)
                                        String idempotencyKey,
                                        @Valid @RequestBody PlanFeeWithholdingRequest body) {
        return PlanFeeWithholdingResponse.of(planFees.withhold(orgId, new PlanFeeService.PlanFeeCommand(
                body.invoiceId(), body.invoiceNumber(), body.amountMinor(), body.currency()), idempotencyKey),
                List.of());
    }

    @Operation(summary = "Release a withheld plan fee back to the organisation's next transfer (seller service, "
            + "payments:plan-fees)",
            description = "Invoice voided or deleted (everything), or refunded out of band (amountMinor). Posts Dr 4600 "
                    + "/ Cr 2000:org; what was already recovered is paid back with the next transfer. Idempotency-Key "
                    + "plan-fee-release-<invoice or credit note>: a replay releases nothing more. A DECLINED or fully "
                    + "released withholding is answered unchanged.")
    @PostMapping("/v1/orgs/{orgId}/plan-fee-withholdings/{invoiceId}/release")
    PlanFeeWithholdingResponse release(@PathVariable String orgId, @PathVariable String invoiceId,
                                       @RequestHeader(value = PaymentController.IDEMPOTENCY_KEY, required = false)
                                       String idempotencyKey,
                                       @Valid @RequestBody(required = false) PlanFeeReleaseRequest body) {
        return PlanFeeWithholdingResponse.of(planFees.release(orgId, invoiceId,
                body == null ? null : body.amountMinor(), body == null ? null : body.reason(), idempotencyKey),
                List.of());
    }

    @Operation(summary = "Plan fees withheld from the organisation's earnings, newest first, with the transfers that "
            + "settled them",
            description = "The seller service (payments:plan-fees), the organisation's members who see payouts, or "
                    + "staff with payments:read. Each deduction is a \"Plan fee (invoice …)\" line of the settlement "
                    + "it reduced.")
    @GetMapping("/v1/orgs/{orgId}/plan-fee-withholdings")
    List<PlanFeeWithholdingResponse> list(@PathVariable String orgId,
                                          @RequestParam(defaultValue = "50") int limit) {
        return planFees.ofOrg(orgId, limit).stream().map(PlanFeeWithholdingResponse::of).toList();
    }
}
