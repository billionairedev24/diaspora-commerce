package com.afrotransact.retail.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.retail.application.OpenReceiptCommand;
import com.afrotransact.retail.application.ReceiptLineInput;
import com.afrotransact.retail.domain.Discrepancy;
import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.LineCheck;
import com.afrotransact.retail.domain.LineVariance;
import com.afrotransact.retail.domain.PostedLot;
import com.afrotransact.retail.domain.QaResult;
import com.afrotransact.retail.domain.ReceiptLine;
import com.afrotransact.retail.domain.ReceiptValidation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies for goods receipts. */
final class ReceiptDtos {

    private ReceiptDtos() {
    }

    @Schema(name = "OpenReceiptRequest", description = "Receive against a shipment's ASN or directly against a PO")
    record OpenReceiptRequest(UUID shipmentId, UUID poId, @Size(max = 40) String dock) {

        OpenReceiptCommand toCommand() {
            return new OpenReceiptCommand(shipmentId, poId, dock);
        }
    }

    @Schema(name = "ReceiptLineRequest", description = "A counted batch; a new lineNo with an existing poLineNo "
            + "splits a mixed batch")
    record ReceiptLineRequest(@Positive int lineNo, @Positive int poLineNo, @Size(max = 20) String lotNo,
                              LocalDate mfgDate, LocalDate expDate, @PositiveOrZero int qtyGood,
                              @PositiveOrZero Integer qtyDamaged, @Size(max = 12) String bin, QaResult qa,
                              @Schema(description = "Case label scanned (GS1-128) rather than keyed") Boolean scanned) {

        ReceiptLineInput toInput() {
            return new ReceiptLineInput(lineNo, poLineNo, lotNo, mfgDate, expDate, qtyGood,
                    qtyDamaged == null ? 0 : qtyDamaged, bin, qa, Boolean.TRUE.equals(scanned));
        }
    }

    @Schema(name = "CountRequest")
    record CountRequest(@NotEmpty @Size(max = 500) List<@Valid ReceiptLineRequest> lines) {

        List<ReceiptLineInput> toInputs() {
            return lines.stream().map(ReceiptLineRequest::toInput).toList();
        }
    }

    @Schema(name = "ClaimResolutionRequest")
    record ClaimResolutionRequest(@NotBlank @Size(max = 1000) String resolution) {
    }

    @Schema(name = "GoodsReceipt")
    record ReceiptResponse(UUID id, String number, UUID poId, String poNumber, UUID shipmentId, String shipmentRef,
                           String nodeId, String dock, String status, List<ReceiptLine> lines,
                           List<ChargeDto> charges, String overApprovedBy, List<Discrepancy> discrepancies,
                           LandedCostResponse landedCost, List<PostedLot> postedLots, UUID journalEntryId,
                           String openedBy, Instant openedAt, String completedBy, Instant completedAt, long version) {

        static ReceiptResponse of(GoodsReceipt r) {
            return new ReceiptResponse(r.id(), r.number(), r.poId(), r.poNumber(), r.shipmentId(), r.shipmentRef(),
                    r.nodeId(), r.dock(), r.status().name(), r.lines(),
                    r.charges().stream().map(ChargeDto::of).toList(), r.overApprovedBy(), r.discrepancies(),
                    LandedCostResponse.of(r.landedCost()), r.postedLots(), r.journalEntryId(), r.openedBy(),
                    r.openedAt(), r.completedBy(), r.completedAt(), r.version());
        }
    }

    @Schema(name = "ReceiptValidation", description = "Errors block completion; warnings explain quarantines and "
            + "claims; variances compare received vs expected per PO line")
    record ValidationResponse(boolean ok, List<LineCheck> lines, List<LineVariance> variances,
                              List<String> receiptErrors, Map<String, String> fieldErrors) {

        static ValidationResponse of(ReceiptValidation v) {
            return new ValidationResponse(v.ok(), v.lines(), v.variances(), v.receiptErrors(), v.fieldErrors());
        }
    }
}
