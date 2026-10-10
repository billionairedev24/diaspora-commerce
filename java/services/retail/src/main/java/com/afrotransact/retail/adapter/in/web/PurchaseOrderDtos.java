package com.afrotransact.retail.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.application.CreatePoCommand;
import com.afrotransact.retail.application.PoLineInput;
import com.afrotransact.retail.domain.PoHistoryEntry;
import com.afrotransact.retail.domain.PoLine;
import com.afrotransact.retail.domain.PurchaseOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request and response bodies for purchase orders. */
final class PurchaseOrderDtos {

    private PurchaseOrderDtos() {
    }

    @Schema(name = "PoLineRequest", description = "Unit cost in the supplier's currency (minor units); omitted = "
            + "the supplier's agreed cost on the item")
    record PoLineRequest(@NotNull UUID itemId, @Positive int qty, @Positive Long unitCostMinor) {

        PoLineInput toInput() {
            return new PoLineInput(itemId, qty, unitCostMinor);
        }
    }

    @Schema(name = "CreatePurchaseOrderRequest")
    record CreatePoRequest(@NotNull UUID supplierId,
                           @Schema(example = "HOU-FC") @NotBlank String destinationNodeId,
                           @Schema(example = "FOB Tema") @NotBlank @Size(max = 80) String incoterm,
                           LocalDate orderDate, LocalDate expectedDate,
                           @Schema(description = "Supplier-currency units per 1 base unit at the PO date, e.g. 15.42 "
                                   + "GHS per USD; omitted = configured rate of the day")
                           @DecimalMin(value = "0", inclusive = false) BigDecimal fxRate,
                           @NotEmpty @Size(max = 200) List<@Valid PoLineRequest> lines) {

        CreatePoCommand toCommand() {
            return new CreatePoCommand(supplierId, destinationNodeId, incoterm, orderDate, expectedDate, fxRate,
                    lines.stream().map(PoLineRequest::toInput).toList());
        }
    }

    @Schema(name = "PoLinesRequest")
    record LinesRequest(@NotEmpty @Size(max = 200) List<@Valid PoLineRequest> lines) {

        List<PoLineInput> toInputs() {
            return lines.stream().map(PoLineRequest::toInput).toList();
        }
    }

    @Schema(name = "NoteRequest")
    record NoteRequest(@Size(max = 1000) String note) {
    }

    @Schema(name = "PoLine")
    record PoLineResponse(int lineNo, UUID itemId, String sku, String description, String supplierSku, int qtyOrdered,
                          long unitCostMinor, long amountMinor, int qtyReceived, int qtyDamaged, int qtyInvoiced,
                          int openQty) {

        static PoLineResponse of(PoLine l) {
            return new PoLineResponse(l.lineNo(), l.itemId(), l.sku(), l.description(), l.supplierSku(),
                    l.qtyOrdered(), l.unitCostMinor(), l.amountMinor(), l.qtyReceived(), l.qtyDamaged(),
                    l.qtyInvoiced(), l.openQty());
        }
    }

    @Schema(name = "PurchaseOrder", description = "Amounts in PO (supplier) currency minor units; totalBaseMinor at "
            + "the captured FX rate")
    record PoResponse(UUID id, String number, UUID supplierId, String supplierCode, String currency,
                      BigDecimal fxRate, String baseCurrency, LocalDate fxDate, String destinationNodeId,
                      String incoterm, LocalDate orderDate, LocalDate expectedDate, String status,
                      String approvalTier, boolean shipmentBooked, long totalMinor, long totalBaseMinor,
                      List<PoLineResponse> lines, List<PoHistoryEntry> history, String createdBy, String approvedBy,
                      Instant createdAt, Instant updatedAt, long version) {

        static PoResponse of(PurchaseOrder po) {
            return new PoResponse(po.id(), po.number(), po.supplierId(), po.supplierCode(), po.currency(),
                    po.fx().unitsPerBase(), po.fx().baseCurrency(), po.fx().asOf(), po.destinationNodeId(),
                    po.incoterm(), po.orderDate(), po.expectedDate(), po.status().name(), po.approvalTier().name(),
                    po.shipmentBooked(), po.totalMinor(), po.totalBaseMinor(),
                    po.lines().stream().map(PoLineResponse::of).toList(), po.history(), po.createdBy(),
                    po.approvedBy(), po.createdAt(), po.updatedAt(), po.version());
        }
    }
}
