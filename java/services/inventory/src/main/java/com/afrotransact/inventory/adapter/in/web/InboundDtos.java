package com.afrotransact.inventory.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.inventory.adapter.in.web.InventoryDtos.LotResponse;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.MovementResponse;
import com.afrotransact.inventory.application.InboundCommands;
import com.afrotransact.inventory.application.InboundShipmentService;
import com.afrotransact.inventory.application.InventoryInsightsService;
import com.afrotransact.inventory.domain.AgeBucket;
import com.afrotransact.inventory.domain.InboundBox;
import com.afrotransact.inventory.domain.InboundDiscrepancy;
import com.afrotransact.inventory.domain.InboundLine;
import com.afrotransact.inventory.domain.InboundShipment;
import com.afrotransact.inventory.domain.PostalAddress;
import com.afrotransact.inventory.port.InventoryInsightsRepository;
import com.afrotransact.inventory.port.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies of inbound shipments (S257, S226), the FBA view (S067) and the hub (S066). */
final class InboundDtos {

    private InboundDtos() {
    }

    // ---- requests ----

    @Schema(name = "PostalAddress")
    record AddressDto(@Size(max = 120) String name, @Size(max = 200) String line1, @Size(max = 200) String line2,
                      @Size(max = 100) String city, @Size(max = 60) String region,
                      @Size(max = 20) String postalCode,
                      @Schema(description = "ISO 3166-1 alpha-2") @Size(min = 2, max = 2) String country,
                      @Size(max = 40) String phone) {

        PostalAddress toDomain() {
            return new PostalAddress(name, line1, line2, city, region, postalCode, country, phone);
        }

        static AddressDto of(PostalAddress a) {
            return a == null ? null : new AddressDto(a.name(), a.line1(), a.line2(), a.city(), a.region(),
                    a.postalCode(), a.country(), a.phone());
        }
    }

    @Schema(name = "InboundPlanRequest", description = "Send stock to AfroTransact: SKUs and quantities, optional "
            + "boxes, requested FC and ship-from address")
    record PlanRequest(@Schema(description = "Staff only: the seller organisation") @Size(max = 80) String ownerOrg,
                       @Size(max = 120) String name,
                       @Schema(description = "Requested fulfilment centre; omit to have one assigned")
                       @Size(max = 20) String nodeId,
                       @Schema(description = "Storage the units need (Ambient, Chilled …)") @Size(max = 30)
                       String storageZone,
                       @Valid AddressDto shipFrom, LocalDate expectedArrival,
                       @NotEmpty @Size(max = 200) List<@Valid PlanLineDto> lines,
                       @Size(max = 200) List<@Valid BoxDto> boxes) {

        InboundCommands.CreatePlan toCommand() {
            return new InboundCommands.CreatePlan(ownerOrg, name, nodeId, storageZone,
                    shipFrom == null ? null : shipFrom.toDomain(), expectedArrival,
                    lines.stream().map(PlanLineDto::toCommand).toList(),
                    boxes == null ? List.of() : boxes.stream().map(BoxDto::toCommand).toList());
        }
    }

    @Schema(name = "InboundPlanUpdateRequest", description = "Replaces the lines and / or boxes of a WORKING plan; "
            + "omitted lists are kept")
    record PlanUpdateRequest(@Size(max = 200) List<@Valid PlanLineDto> lines,
                             @Size(max = 200) List<@Valid BoxDto> boxes) {

        InboundCommands.CreatePlan toCommand() {
            return new InboundCommands.CreatePlan(null, null, null, null, null, null,
                    lines == null ? List.of() : lines.stream().map(PlanLineDto::toCommand).toList(),
                    boxes == null ? List.of() : boxes.stream().map(BoxDto::toCommand).toList());
        }
    }

    @Schema(name = "InboundPlanLine")
    record PlanLineDto(@NotBlank @Size(max = 10) String atin,
                       @Schema(description = "Inventory SKU (the FBA offer's inventory SKU); default: your AT-FN unit "
                               + "label code") @Size(max = 64) String sku,
                       @Min(1) @Max(100_000) int qty,
                       @Schema(description = "Units per case; qty must be whole cases") @Min(1) @Max(10_000)
                       Integer casePack,
                       @Schema(description = "Batch / lot number on the packing list (GS1 AI 10)") @Size(max = 59)
                       String lotNo,
                       @Schema(description = "Manufacture date of the batch (AI 11)") LocalDate mfgDate,
                       @Schema(description = "Expiry / best-before date of the batch (AI 17); required by the "
                               + "consoles for food and perishable product types") LocalDate expDate) {

        InboundCommands.PlanLine toCommand() {
            return new InboundCommands.PlanLine(atin, sku, qty, casePack, lotNo, mfgDate, expDate);
        }
    }

    @Schema(name = "InboundBoxInput")
    record BoxDto(@NotEmpty @Size(max = 200) List<@Valid BoxItemDto> contents,
                  @DecimalMin(value = "0.01") @DecimalMax(value = "150") BigDecimal weightLb,
                  @PositiveOrZero BigDecimal lengthIn, @PositiveOrZero BigDecimal widthIn,
                  @PositiveOrZero BigDecimal heightIn) {

        InboundCommands.BoxInput toCommand() {
            return new InboundCommands.BoxInput(contents.stream().map(i -> new InboundCommands.BoxItem(i.sku(),
                    i.qty())).toList(), weightLb, lengthIn, widthIn, heightIn);
        }
    }

    @Schema(name = "InboundBoxItem")
    record BoxItemDto(@NotBlank @Size(max = 64) String sku, @Min(1) int qty) {
    }

    @Schema(name = "InboundTrackingRequest", description = "Carrier and tracking number of a label bought elsewhere")
    record TrackingRequest(@NotBlank @Schema(description = "usps, ups, fedex, dhl …") @Size(max = 40) String carrier,
                           @NotBlank @Size(max = 60) String trackingNumber) {
    }

    @Schema(name = "InboundLabelPurchaseRequest", description = "Buy the inbound label through carrier-gateway "
            + "(carrier and service from a rate quote); one parcel per box")
    record BuyLabelRequest(@NotBlank @Size(max = 40) String carrier, @NotBlank @Size(max = 60) String service,
                           LocalDate shipDate) {
    }

    @Schema(name = "InboundReceiveRequest", description = "What the FC counted against the plan")
    record ReceiveRequest(@Size(max = 500) List<@Valid ReceiveLineDto> lines,
                          @Schema(description = "Carton LPNs scanned at the dock") @Size(max = 500)
                          List<@NotBlank @Size(max = 20) String> boxesReceived) {

        InboundCommands.ReceivePlan toCommand() {
            return new InboundCommands.ReceivePlan(lines == null ? List.of() : lines.stream()
                    .map(l -> new InboundCommands.ReceiveLine(l.sku(), l.unitLabel(), null, l.qtyGood(),
                            l.qtyDamaged(), l.lotNo(), l.mfgDate(), l.expDate(), l.bin(), l.holdReason())).toList(),
                    boxesReceived);
        }
    }

    @Schema(name = "InboundReceiveLine", description = "Units of one SKU, by SKU or by the scanned AT-FN unit label")
    record ReceiveLineDto(@Size(max = 64) String sku,
                          @Schema(description = "Scanned AT-FN unit label (ATF + 7)") @Size(max = 20) String unitLabel,
                          @PositiveOrZero int qtyGood, @PositiveOrZero int qtyDamaged,
                          @Schema(description = "Default: one lot per receipt") @Size(max = 59) String lotNo,
                          LocalDate mfgDate, LocalDate expDate, @Size(max = 30) String bin,
                          @Schema(description = "Receive the good units into quarantine with this reason")
                          @Size(max = 500) String holdReason) {
    }

    // ---- responses ----

    @Schema(name = "InboundLine")
    record LineResponse(int lineNo, String sku, String atin,
                        @Schema(description = "AT-FN unit label code (seller-specific)") String unitLabel,
                        int expectedQty, int receivedGood, int receivedDamaged, int shortQty, int overQty,
                        @Schema(description = "Expected units still counted as inbound") int inboundOpen,
                        @Schema(description = "Units per case (null when not case-packed)") Integer casePack,
                        @Schema(description = "Cases announced (0 when not case-packed)") int cases,
                        @Schema(description = "Announced batch (packing list)") String lotNo, LocalDate mfgDate,
                        LocalDate expDate) {

        static LineResponse of(InboundLine l) {
            return new LineResponse(l.lineNo(), l.sku(), l.atin(), l.unitLabel(), l.expectedQty(), l.receivedGood(),
                    l.receivedDamaged(), l.shortQty(), l.overQty(), l.inboundOpen(), l.batch().casePack(),
                    l.batch().cases(l.expectedQty()), l.batch().lotNo(), l.batch().mfgDate(), l.batch().expDate());
        }
    }

    @Schema(name = "InboundBox")
    record BoxResponse(int boxNo, @Schema(description = "Carton license plate (LPN + 8)") String lpn,
                       List<InboundBox.Item> contents, int units, BigDecimal weightLb, BigDecimal lengthIn,
                       BigDecimal widthIn, BigDecimal heightIn, Instant receivedAt) {

        static BoxResponse of(InboundBox b) {
            return new BoxResponse(b.boxNo(), b.lpn(), b.contents(), b.units(), b.weightLb(), b.lengthIn(),
                    b.widthIn(), b.heightIn(), b.receivedAt());
        }
    }

    @Schema(name = "InboundShipment", description = "An FBA inbound plan or a retail ASN with what was received")
    record ShipmentResponse(UUID id, String number,
                            @Schema(description = "FBA | RETAIL_ASN") String source, String externalRef,
                            String ownerOrg, String nodeId, String requestedNodeId, String assignmentReason,
                            String name,
                            @Schema(description = "WORKING | SHIPPED | RECEIVING | CLOSED | CANCELLED") String status,
                            AddressDto shipFrom, AddressDto shipTo, String carrier, String trackingNumber,
                            @Schema(description = "SELLER | CARRIER_GATEWAY") String trackingSource,
                            String carrierLabelId, Long labelCostMinor, String labelCurrency,
                            String labelDocumentUrl, LocalDate expectedArrival, int expectedUnits,
                            int receivedUnits, int receiptCount, boolean discrepancy,
                            List<InboundDiscrepancy> discrepancies, List<LineResponse> lines,
                            List<BoxResponse> boxes, String createdBy, Instant createdAt, Instant shippedAt,
                            Instant closedAt, Instant updatedAt, long version) {

        static ShipmentResponse of(InboundShipment s, PostalAddress shipTo) {
            List<InboundDiscrepancy> found = s.discrepancies();
            return new ShipmentResponse(s.id(), s.number(), s.source().name(), s.externalRef(), s.ownerOrg(),
                    s.nodeId(), s.requestedNodeId(), s.assignmentReason(), s.name(), s.status().name(),
                    AddressDto.of(s.shipFrom()), AddressDto.of(shipTo), s.carrier(), s.trackingNumber(),
                    s.trackingSource(), s.carrierLabelId(), s.labelCostMinor(), s.labelCurrency(),
                    s.labelDocumentUrl(), s.expectedArrival(), s.expectedUnits(), s.receivedUnits(),
                    s.receiptCount(), !found.isEmpty(), found, s.lines().stream().map(LineResponse::of).toList(),
                    s.boxes().stream().map(BoxResponse::of).toList(), s.createdBy(), s.createdAt(), s.shippedAt(),
                    s.closedAt(), s.updatedAt(), s.version());
        }
    }

    @Schema(name = "InboundShipmentPage")
    record ShipmentPage(List<ShipmentResponse> items, long total, int page, int size) {
    }

    @Schema(name = "InboundTrackingResult")
    record TrackingResponse(ShipmentResponse shipment,
                            @Schema(description = "carrier-gateway tracks the number (scans update the shipment)")
                            boolean trackingRegistered) {
    }

    @Schema(name = "InboundReceiptResult", description = "The shipment after the receipt and the lots it created")
    record ReceiptResponse(ShipmentResponse shipment, String receiptRef, List<LotResponse> lots, boolean replayed) {

        static ReceiptResponse of(InboundShipmentService.ReceiptOutcome o, PostalAddress shipTo) {
            return new ReceiptResponse(ShipmentResponse.of(o.shipment(), shipTo),
                    o.receipt() == null ? null : o.receipt().receiptRef(),
                    o.receipt() == null ? List.of() : o.receipt().lots().stream().map(LotResponse::of).toList(),
                    o.receipt() != null && o.receipt().replayed());
        }
    }

    // ---- FBA inventory (S067) ----

    @Schema(name = "FbaInventoryItem", description = "FBA stock of one SKU at one fulfilment centre")
    record FbaItemResponse(String sku, String atin, String unitLabel, String nodeId, int onHand, int reserved,
                           int available, int inbound,
                           @Schema(description = "Held units: quarantined, damaged and expired") int unfulfillable,
                           int quarantined, int damaged, int expired,
                           @Schema(description = "Units per age since receipt (D0_90 … D365_PLUS)")
                           Map<AgeBucket, Integer> ageing, Instant oldestReceivedAt, BigDecimal unitCubicFeet,
                           BigDecimal cubicFeet, boolean measured, int openOffers, int liveOffers, boolean stranded,
                           @Schema(description = "NO_OFFER | OFFER_INACTIVE") String strandedReason) {

        static FbaItemResponse of(InventoryInsightsService.FbaItem i) {
            InventoryInsightsRepository.FbaStockRow r = i.stock();
            return new FbaItemResponse(r.sku(), r.atin(), r.unitLabel(), r.nodeId(), r.onHand(), r.reserved(),
                    r.available(), r.inbound(), r.unfulfillable(), r.quarantined(), r.damagedLots(), r.expired(),
                    r.ageing(), r.oldestReceivedAt(), r.unitCubicFeet(), i.cubicFeet(), i.measured(), r.openOffers(),
                    r.liveOffers(), i.stranded(), i.strandedReason());
        }
    }

    @Schema(name = "FbaInventory", description = "A seller's stock in AfroTransact fulfilment centres")
    record FbaInventoryResponse(String ownerOrg, List<FbaItemResponse> items, long total, int page, int size,
                                InventoryInsightsService.FbaSummary summary) {

        static FbaInventoryResponse of(InventoryInsightsService.FbaInventory v) {
            Page<InventoryInsightsService.FbaItem> p = v.items();
            return new FbaInventoryResponse(v.ownerOrg(), p.items().stream().map(FbaItemResponse::of).toList(),
                    p.total(), p.page(), p.size(), v.summary());
        }
    }

    // ---- lot trace (S066) ----

    @Schema(name = "LotTrace", description = "A lot's recall trace: ledger, related lots and the orders that took "
            + "its units")
    record LotTraceResponse(LotResponse lot, List<MovementResponse> movements, List<LotResponse> relatedLots,
                            List<InventoryInsightsRepository.LotConsumer> orders) {

        static LotTraceResponse of(InventoryInsightsService.LotTrace t) {
            return new LotTraceResponse(LotResponse.of(t.lot()), t.movements().stream().map(MovementResponse::of)
                    .toList(), t.relatedLots().stream().map(LotResponse::of).toList(), t.orders());
        }
    }
}
