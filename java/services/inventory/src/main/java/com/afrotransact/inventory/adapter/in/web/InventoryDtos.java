package com.afrotransact.inventory.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.application.CommitResult;
import com.afrotransact.inventory.application.ReceiptResult;
import com.afrotransact.inventory.application.ReceiveCommand;
import com.afrotransact.inventory.application.ReserveCommand;
import com.afrotransact.inventory.application.ReturnResult;
import com.afrotransact.inventory.domain.CostComponents;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.Movement;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.NodeKind;
import com.afrotransact.inventory.domain.PositionDrift;
import com.afrotransact.inventory.domain.ReconciliationReport;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationLine;
import com.afrotransact.inventory.domain.ReturnCondition;
import com.afrotransact.inventory.domain.StockLevels;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the inventory API. */
final class InventoryDtos {

    private InventoryDtos() {
    }

    // ---- nodes ----

    @Schema(name = "Node", description = "Fulfilment centre, origin hub or warehouse")
    record NodeResponse(String id, String name, NodeKind kind, String country, String timezone,
                        List<String> temperatureZones, boolean active) {

        static NodeResponse of(Node n) {
            return new NodeResponse(n.id(), n.name(), n.kind(), n.country(), n.timezone(), n.temperatureZones(),
                    n.active());
        }
    }

    @Schema(name = "CreateNodeRequest")
    record CreateNodeRequest(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{1,19}") String id,
                             @NotBlank @Size(max = 120) String name, @NotNull NodeKind kind,
                             @NotBlank @Size(min = 2, max = 2) String country, @NotBlank String timezone,
                             List<@NotBlank String> temperatureZones) {

        Node toDomain() {
            return new Node(id, name, kind, country, timezone, temperatureZones, true);
        }
    }

    // ---- availability & positions ----

    @Schema(name = "Position", description = "Stock of one SKU at one node; available = onHand − reserved")
    record PositionResponse(String sku, String nodeId, String ownerOrg, int onHand, int reserved, int available,
                            int inbound, int damaged, long version) {

        static PositionResponse of(StockPosition p) {
            return new PositionResponse(p.sku(), p.nodeId(), p.ownerOrg(), p.onHand(), p.reserved(), p.available(),
                    p.inbound(), p.damaged(), p.version());
        }
    }

    @Schema(name = "PositionPage")
    record PositionPage(List<PositionResponse> items, long total, int page, int size) {

        static PositionPage of(Page<StockPosition> p) {
            return new PositionPage(p.items().stream().map(PositionResponse::of).toList(), p.total(), p.page(),
                    p.size());
        }
    }

    // ---- lots ----

    @Schema(name = "Lot", description = "A batch of one SKU at one node with dates, bin, unit landed cost and status. "
            + "The cost is kept in the currency paid with the rate to USD fixed at receiving and the USD value "
            + "valuation and COGS use.")
    record LotResponse(UUID id, String sku, String lotNo, String nodeId, String ownerOrg, String bin, int qtyOnHand,
                       int qtyReserved, int qtyAvailable, LocalDate mfgDate, LocalDate expDate, Instant receivedAt,
                       @Schema(description = "Unit landed cost in minor units of `currency`")
                       long unitLandedCostMinor,
                       @Schema(description = "ISO 4217 code of the currency the lot was paid in") String currency,
                       @Schema(description = "US dollars per 1 unit of `currency` when the lot was received")
                       BigDecimal fxRateToUsd,
                       @Schema(description = "The day the rate is for") LocalDate fxRateDate,
                       @Schema(description = "BASE (USD), ENTERED (by the receiver or sender) or TABLE (the "
                               + "staff-managed rates)") String fxRateSource,
                       @Schema(description = "Unit landed cost in US cents, fixed at receiving") long unitCostUsdMinor,
                       @Schema(description = "Unit cost by goods, freight, duty and other, in `currency`")
                       CostComponentsDto costComponents, String status, String reason, String markdownFlag,
                       String receiptRef, long version) {

        static LotResponse of(Lot l) {
            return new LotResponse(l.id(), l.sku(), l.lotNo(), l.nodeId(), l.ownerOrg(), l.bin(), l.qtyOnHand(),
                    l.qtyReserved(), l.available(), l.mfgDate(), l.expDate(), l.receivedAt(), l.unitLandedCostMinor(),
                    l.currency(), l.cost().rateToUsd(), l.cost().rateDate(), l.cost().rateSource().name(),
                    l.unitCostUsdMinor(), CostComponentsDto.of(l.cost().components()), l.status().name(), l.reason(),
                    l.markdownFlag().name(), l.receiptRef(), l.version());
        }
    }

    @Schema(name = "CostComponents", description = "Unit landed cost by what it is made of, in the lot's currency and "
            + "minor units; the four add up to the unit cost")
    record CostComponentsDto(@PositiveOrZero long goodsMinor, @PositiveOrZero long freightMinor,
                             @PositiveOrZero long dutyMinor, @PositiveOrZero long otherMinor) {

        static CostComponentsDto of(CostComponents c) {
            return new CostComponentsDto(c.goodsMinor(), c.freightMinor(), c.dutyMinor(), c.otherMinor());
        }

        static CostComponents toDomain(CostComponentsDto c) {
            return c == null ? null : new CostComponents(c.goodsMinor(), c.freightMinor(), c.dutyMinor(),
                    c.otherMinor());
        }
    }

    @Schema(name = "LotPage")
    record LotPage(List<LotResponse> items, long total, int page, int size) {

        static LotPage of(Page<Lot> p) {
            return new LotPage(p.items().stream().map(LotResponse::of).toList(), p.total(), p.page(), p.size());
        }
    }

    @Schema(name = "ReasonRequest")
    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "WriteOffRequest", description = "qty omitted = all unreserved units of the lot")
    record WriteOffRequest(@Positive Integer qty, @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "CycleCountRequest", description = "Counted quantity of the lot; the difference is posted as ADJUST")
    record CycleCountRequest(@PositiveOrZero int countedQty, @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "TransferRequest")
    record TransferRequest(@NotBlank String toNodeId, @Size(max = 30) String toBin, @Positive int qty,
                           @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "TransferResult")
    record TransferResponse(String reference, LotResponse from, LotResponse to) {
    }

    // ---- receipts ----

    @Schema(name = "ReceiptRequest", description = "Lots of a completed goods receipt")
    record ReceiptRequest(@NotBlank @Size(max = 80) String receiptRef, @NotBlank String nodeId,
                          @Schema(description = "FBA seller org; omit for AfroTransact Retail (1P)") String ownerOrg,
                          @Schema(description = "Inbound shipment the units arrived on (FBA plan number or retail "
                                  + "shipment reference): its expected units are consumed and the received "
                                  + "quantities recorded against it (S226, S257)") @Size(max = 120) String inboundRef,
                          @NotEmpty @Size(max = 500) List<@Valid ReceiptLine> lines) {

        ReceiveCommand toCommand() {
            return new ReceiveCommand(receiptRef, nodeId, ownerOrg, inboundRef,
                    lines.stream().map(ReceiptLine::toDomain).toList());
        }
    }

    @Schema(name = "ReceiptLine")
    record ReceiptLine(@NotBlank @Size(max = 64) String sku, @NotBlank @Size(max = 59) String lotNo,
                       LocalDate mfgDate, LocalDate expDate, @PositiveOrZero int qtyGood,
                       @PositiveOrZero int qtyDamaged, @Size(max = 30) String bin,
                       @Schema(description = "Unit landed cost in minor units of `currency`")
                       @PositiveOrZero long unitLandedCostMinor,
                       @Schema(description = "The currency actually paid: USD or one of GET /v1/currencies")
                       @NotBlank @Size(min = 3, max = 3) String currency,
                       @Schema(description = "Shelf life from manufacture in days (short-dated rule)")
                       @Positive Integer shelfLifeDays,
                       @Schema(description = "Receive the good units straight into QUARANTINE with this reason "
                               + "(e.g. QC hold at the dock); omit for sellable stock")
                       @Size(max = 500) String holdReason,
                       @Schema(description = "US dollars per 1 unit of `currency` (> 0, at most 10 decimals); omit "
                               + "to use the latest rate on or before the receiving date in GET /v1/exchange-rates; "
                               + "not needed for USD")
                       @Positive BigDecimal fxRateToUsd,
                       @Schema(description = "The day `fxRateToUsd` is for; omit for the receiving date")
                       LocalDate fxRateDate,
                       @Schema(description = "Unit cost in US cents when the sender already valued it (needs "
                               + "`fxRateToUsd`); omit to use unit cost × rate") @PositiveOrZero Long unitCostUsdMinor,
                       @Schema(description = "Goods, freight, duty and other per unit in `currency`; must add up "
                               + "to `unitLandedCostMinor`; omit for all goods") @Valid CostComponentsDto costComponents) {

        ReceiveCommand.Line toDomain() {
            return new ReceiveCommand.Line(sku, lotNo, mfgDate, expDate, qtyGood, qtyDamaged, bin, unitLandedCostMinor,
                    currency, shelfLifeDays, holdReason, fxRateToUsd, fxRateDate, unitCostUsdMinor,
                    CostComponentsDto.toDomain(costComponents));
        }
    }

    @Schema(name = "ReceiptResult")
    record ReceiptResponse(String receiptRef, String nodeId, String ownerOrg, List<LotResponse> lots,
                           boolean replayed) {

        static ReceiptResponse of(ReceiptResult r) {
            return new ReceiptResponse(r.receiptRef(), r.nodeId(), r.ownerOrg(),
                    r.lots().stream().map(LotResponse::of).toList(), r.replayed());
        }
    }

    @Schema(name = "InboundRequest", description = "Expected units from an advance shipping notice")
    record InboundRequest(@NotBlank String sku, @NotBlank String nodeId, String ownerOrg, int qty,
                          @Size(max = 120) String reference) {
    }

    // ---- reservations ----

    @Schema(name = "ReserveRequest", description = "All lines are reserved or none; orderId makes the call idempotent")
    record ReserveRequest(@NotBlank @Size(max = 80) String orderId,
                          @NotEmpty @Size(max = 100) List<@Valid ReserveLine> lines) {

        ReserveCommand toCommand(String idempotencyKey) {
            return new ReserveCommand(orderId, idempotencyKey,
                    lines.stream().map(l -> new ReserveCommand.Line(l.sku(), l.nodeId(), l.qty(), l.group())).toList());
        }
    }

    @Schema(name = "ReserveLine")
    record ReserveLine(@NotBlank @Size(max = 64) String sku,
                       @Schema(description = "Node chosen by the promise service; omit to let inventory choose")
                       String nodeId,
                       @Min(1) @Max(10_000) int qty,
                       @Schema(description = "Caller's grouping key (orders: the order line id); groups can be "
                               + "committed or released on their own (one shipment at a time)")
                       @Size(max = 80) String group) {
    }

    @Schema(name = "ReleaseRequest")
    record ReleaseRequest(@Size(max = 500) String reason) {
    }

    @Schema(name = "ReservationGroupsRequest", description = "The groups (orders: line ids of one shipment) to "
            + "commit or release")
    record GroupsRequest(@NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 80) String> groups,
                         @Size(max = 500) String reason) {
    }

    @Schema(name = "LotAllocation")
    record AllocationResponse(UUID lotId, String lotNo, int qty, LocalDate expDate, long unitLandedCostMinor,
                              String currency,
                              @Schema(description = "Unit landed cost of the lot in US cents (what COGS uses)")
                              long unitCostUsdMinor) {

        static AllocationResponse of(LotAllocation a) {
            return new AllocationResponse(a.lotId(), a.lotNo(), a.qty(), a.expDate(), a.unitLandedCostMinor(),
                    a.currency(), a.unitCostUsdMinor());
        }
    }

    @Schema(name = "ReservationLine")
    record ReservationLineResponse(int lineNo, String sku, String nodeId, int qty,
                                   List<AllocationResponse> allocations, String group,
                                   @Schema(description = "HELD, COMMITTED or RELEASED") String status,
                                   String stockOwnerOrgId) {

        static ReservationLineResponse of(ReservationLine l) {
            return new ReservationLineResponse(l.lineNo(), l.sku(), l.nodeId(), l.qty(),
                    l.allocations().stream().map(AllocationResponse::of).toList(), l.group(), l.status().name(),
                    l.stockOwnerOrgId());
        }
    }

    @Schema(name = "Reservation")
    record ReservationResponse(UUID id, String orderId, String status, Instant expiresAt, Instant createdAt,
                               Instant updatedAt, List<ReservationLineResponse> lines, boolean replayed) {

        static ReservationResponse of(Reservation r, boolean replayed) {
            return new ReservationResponse(r.id(), r.orderId(), r.status().name(), r.expiresAt(), r.createdAt(),
                    r.updatedAt(), r.lines().stream().map(ReservationLineResponse::of).toList(), replayed);
        }
    }

    @Schema(name = "CommitResult", description = "Shipped reservation with COGS at lot landed cost: in USD "
            + "(`cogsUsdMinor`, what margin uses) and per currency the lots were paid in (`cogs`)")
    record CommitResponse(ReservationResponse reservation, List<CommitResult.Money> cogs,
                          @Schema(description = "COGS in US cents") long cogsUsdMinor,
                          List<CommitResult.LineCost> lines, boolean replayed) {

        static CommitResponse of(CommitResult r) {
            return new CommitResponse(ReservationResponse.of(r.reservation(), r.replayed()), r.cogs(),
                    r.cogsUsdMinor(), r.lines(), r.replayed());
        }
    }

    // ---- returns ----

    @Schema(name = "ReturnRequest", description = "A returned item after inspection")
    record ReturnRequest(@NotBlank String orderId, @NotBlank String sku, UUID lotId, @Positive int qty,
                         @NotNull ReturnCondition condition, @NotBlank @Size(max = 500) String reason,
                         @Schema(description = "Warehouse that received the parcel (e.g. the return centre); default "
                                 + "= the node the unit shipped from") @Size(max = 20) String receivingNodeId) {
    }

    @Schema(name = "ReturnResult")
    record ReturnResponse(String orderId, String sku, int qty, ReturnCondition condition, LotResponse lot,
                          boolean replayed) {

        static ReturnResponse of(ReturnResult r) {
            return new ReturnResponse(r.orderId(), r.sku(), r.qty(), r.condition(), LotResponse.of(r.lot()),
                    r.replayed());
        }
    }

    // ---- ledger ----

    @Schema(name = "Movement", description = "One append-only ledger row with deltas and before/after levels")
    record MovementResponse(long id, Instant occurredAt, String type, String sku, String nodeId, String ownerOrg,
                            UUID lotId, String lotNo, int qty, StockLevels delta, StockLevels before,
                            StockLevels after, String reference, String actor, String reason, String correlationId) {

        static MovementResponse of(Movement m) {
            return new MovementResponse(m.id(), m.occurredAt(), m.type().name(), m.sku(), m.nodeId(), m.ownerOrg(),
                    m.lotId(), m.lotNo(), m.qty(), m.delta(), m.before(), m.after(), m.reference(), m.actor(),
                    m.reason(), m.correlationId());
        }
    }

    @Schema(name = "MovementPage")
    record MovementPage(List<MovementResponse> items, long total, int page, int size) {

        static MovementPage of(Page<Movement> p) {
            return new MovementPage(p.items().stream().map(MovementResponse::of).toList(), p.total(), p.page(),
                    p.size());
        }
    }

    // ---- reconciliation ----

    @Schema(name = "ReconciliationRun")
    record ReconciliationResponse(UUID id, Instant ranAt, int positionsChecked, long driftUnits, boolean clean,
                                  List<PositionDrift> drifts) {

        static ReconciliationResponse of(ReconciliationReport r) {
            return new ReconciliationResponse(r.id(), r.ranAt(), r.positionsChecked(), r.driftUnits(), r.clean(),
                    r.drifts());
        }
    }
}
