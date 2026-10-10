package com.afrotransact.taxreturns.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.adapter.in.web.ReturnDtos.RefundLineResponse;
import com.afrotransact.taxreturns.application.AdjustmentLine;
import com.afrotransact.taxreturns.application.RefundService;
import com.afrotransact.taxreturns.domain.returns.Refund;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Refunds", description = "Refund orchestration: partial refunds, refund history and retries")
@RestController
class RefundController {

    private final RefundService refunds;

    RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @Schema(name = "AdjustmentLine")
    record AdjustmentLineDto(@NotBlank String lineId, @Min(0) long itemsMinor, @Min(0) long shippingMinor) {
    }

    @Schema(name = "CreateRefund", description = "Partial refund of amounts of an order's lines; tax follows "
            + "proportionally (refunds:issue)")
    record CreateRefund(@NotNull UUID orderId, @NotEmpty @Size(max = 200) List<@Valid AdjustmentLineDto> lines,
                        @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "Refund")
    record RefundResponse(UUID id, String kind, UUID rmaId, UUID orderId, UUID paymentId, String currency,
                          String status, long itemsMinor, long shippingMinor, long taxMinor, long restockingFeeMinor,
                          long totalMinor, String reason, String paymentsRefundId, String failure, int attempts,
                          List<RefundLineResponse> lines, Instant createdAt, Instant issuedAt) {

        static RefundResponse of(Refund r) {
            return new RefundResponse(r.id(), r.kind().name(), r.rmaId(), r.orderId(), r.paymentId(), r.currency(),
                    r.status().name(), r.breakdown().itemsMinor(), r.breakdown().shippingMinor(),
                    r.breakdown().taxMinor(), r.breakdown().restockingFeeMinor(), r.breakdown().totalMinor(),
                    r.reason(), r.paymentsRefundId(), r.failure(), r.attempts(),
                    r.lines().stream().map(RefundLineResponse::of).toList(), r.createdAt(), r.issuedAt());
        }
    }

    @Operation(summary = "Partial refund without return (Idempotency-Key; staff or the seller of every line)",
            description = "Calls payments POST /v1/payments/{id}/refunds with Idempotency-Key = refund id; payments "
                    + "reverses the seller transfer portion and commission. AfroTransact Retail lines have neither "
                    + "(payments debits Retail sales; nothing is transferred). The tax reversal is booked in the "
                    + "ledger.")
    @PostMapping("/v1/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    RefundResponse create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                          @Valid @RequestBody CreateRefund body) {
        return RefundResponse.of(refunds.adjust(body.orderId(), body.lines().stream()
                .map(l -> new AdjustmentLine(l.lineId(), l.itemsMinor(), l.shippingMinor())).toList(), body.reason(),
                idempotencyKey));
    }

    @Operation(summary = "Refunds of an order or of a return (staff: refunds:issue or returns:manage)")
    @GetMapping("/v1/refunds")
    List<RefundResponse> list(@RequestParam(required = false) UUID orderId,
                              @RequestParam(required = false) UUID rmaId) {
        if ((orderId == null) == (rmaId == null)) {
            throw new IllegalArgumentException("Give exactly one of orderId or rmaId");
        }
        List<Refund> found = orderId != null ? refunds.forOrder(orderId) : refunds.forRma(rmaId);
        return found.stream().map(RefundResponse::of).toList();
    }

    @Operation(summary = "A refund")
    @GetMapping("/v1/refunds/{id}")
    RefundResponse get(@PathVariable UUID id) {
        return RefundResponse.of(refunds.get(id));
    }

    @Operation(summary = "Send a pending or failed refund to payments again (same Idempotency-Key; refunds:issue)")
    @PostMapping("/v1/refunds/{id}/retry")
    RefundResponse retry(@PathVariable UUID id) {
        return RefundResponse.of(refunds.retry(id));
    }
}
