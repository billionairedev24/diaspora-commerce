package com.afrotransact.inventory.adapter.in.web;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.inventory.adapter.in.web.InboundDtos.BuyLabelRequest;
import com.afrotransact.inventory.adapter.in.web.InboundDtos.PlanRequest;
import com.afrotransact.inventory.adapter.in.web.InboundDtos.PlanUpdateRequest;
import com.afrotransact.inventory.adapter.in.web.InboundDtos.ReceiptResponse;
import com.afrotransact.inventory.adapter.in.web.InboundDtos.ReceiveRequest;
import com.afrotransact.inventory.adapter.in.web.InboundDtos.ShipmentPage;
import com.afrotransact.inventory.adapter.in.web.InboundDtos.ShipmentResponse;
import com.afrotransact.inventory.adapter.in.web.InboundDtos.TrackingRequest;
import com.afrotransact.inventory.adapter.in.web.InboundDtos.TrackingResponse;
import com.afrotransact.inventory.application.InboundCommands;
import com.afrotransact.inventory.application.InboundShipmentService;
import com.afrotransact.inventory.domain.InboundShipment;
import com.afrotransact.inventory.domain.InboundSource;
import com.afrotransact.inventory.domain.InboundStatus;
import com.afrotransact.inventory.port.InboundShipmentRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.inventory.port.UnitLabelClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Inbound shipments", description = "Send stock to AfroTransact (S257): seller FBA plans with AT-FN unit "
        + "labels and carton labels, tracking, FC receiving against the plan with discrepancies; AfroTransact Retail "
        + "ASNs (S226). Sellers: inventory:inbound (own org); FC staff: inventory:receive.")
@RestController
@RequestMapping("/v1/inbound/shipments")
class InboundShipmentController {

    private final InboundShipmentService inbound;

    InboundShipmentController(InboundShipmentService inbound) {
        this.inbound = inbound;
    }

    @Operation(operationId = "createInboundPlan", summary = "Create an inbound plan: SKUs and quantities to send",
            description = "Each line names a variant ATIN; its unit label is the seller's AT-FN code from catalog "
                    + "(issued once per seller and variant, so units of different sellers never mix), which is also "
                    + "the default inventory SKU. A destination fulfilment centre is assigned (never one where "
                    + "another seller stocks the SKU) unless nodeId is requested. Boxes get LPN carton labels. "
                    + "Returns the plan in WORKING.")
    @PostMapping
    ResponseEntity<ShipmentResponse> create(@Valid @RequestBody PlanRequest body) {
        InboundShipment s = inbound.create(body.toCommand());
        return ResponseEntity.created(URI.create("/v1/inbound/shipments/" + s.id())).eTag(etag(s))
                .body(view(s));
    }

    @Operation(operationId = "listInboundShipments", summary = "List inbound shipments (sellers: their own)")
    @GetMapping
    ShipmentPage list(@RequestParam(required = false) String ownerOrg,
                      @RequestParam(required = false) String nodeId,
                      @RequestParam(required = false) InboundStatus status,
                      @RequestParam(required = false) InboundSource source,
                      @RequestParam(required = false) String sku,
                      @RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "25") int size) {
        Page<InboundShipment> p = inbound.list(new InboundShipmentRepository.InboundQuery(ownerOrg, nodeId, status,
                source, sku, page, size));
        return new ShipmentPage(p.items().stream().map(this::view).toList(), p.total(), p.page(), p.size());
    }

    @Operation(operationId = "getInboundShipment", summary = "One inbound shipment with lines, boxes, received "
            + "quantities and discrepancies")
    @GetMapping("/{id}")
    ResponseEntity<ShipmentResponse> get(@PathVariable UUID id) {
        InboundShipment s = inbound.get(id);
        return ResponseEntity.ok().eTag(etag(s)).body(view(s));
    }

    @Operation(operationId = "updateInboundPlan", summary = "Replace the SKUs and / or boxes of a WORKING plan",
            description = "If-Match with the ETag: 409 inventory.version-mismatch when the plan changed meanwhile.")
    @PutMapping("/{id}")
    ResponseEntity<ShipmentResponse> update(@PathVariable UUID id, @Valid @RequestBody PlanUpdateRequest body,
                                            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                            String ifMatch) {
        InboundShipment s = inbound.updatePlan(id, body.toCommand(), version(ifMatch));
        return ResponseEntity.ok().eTag(etag(s)).body(view(s));
    }

    @Operation(operationId = "printInboundLabels", summary = "Unit (AT-FN) or carton (LPN) labels as PDF or ZPL",
            description = "UNIT: one label per planned unit with the seller's AT-FN code (catalog renders it); "
                    + "BOX: one carton label per box with its LPN, the plan number and the FC. lineNo limits unit "
                    + "labels to one line. 503 inventory.catalog-unavailable when catalog cannot render.")
    @GetMapping(path = "/{id}/labels", produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.TEXT_PLAIN_VALUE,
        MediaType.APPLICATION_PROBLEM_JSON_VALUE})
    ResponseEntity<byte[]> labels(@PathVariable UUID id,
                                  @Parameter(schema = @Schema(allowableValues = {"UNIT", "BOX"}))
                                  @RequestParam(defaultValue = "UNIT") InboundShipmentService.LabelKind kind,
                                  @Parameter(schema = @Schema(allowableValues = {"PDF", "ZPL"}))
                                  @RequestParam(defaultValue = "PDF") String format,
                                  @RequestParam(required = false) Integer lineNo) {
        UnitLabelClient.RenderedLabels r = inbound.labels(id, kind, format, lineNo);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(kind.name().toLowerCase(Locale.ROOT) + "-" + r.fileName()).build()
                        .toString())
                .body(r.content());
    }

    @Operation(operationId = "setInboundTracking", summary = "Carrier and tracking number entered by the seller",
            description = "Registered with carrier-gateway for scans when it can (trackingRegistered).")
    @PutMapping("/{id}/tracking")
    TrackingResponse track(@PathVariable UUID id, @Valid @RequestBody TrackingRequest body) {
        InboundShipmentService.TrackingResult r = inbound.track(id, body.carrier(), body.trackingNumber());
        return new TrackingResponse(view(r.shipment()), r.trackingRegistered());
    }

    @Operation(operationId = "buyInboundLabel", summary = "Buy the inbound shipping label through carrier-gateway",
            description = "One parcel per box (weights required), from the ship-from address to the FC. Stores the "
                    + "carrier, tracking number, cost and document link. 503 inventory.carrier-unavailable, 422 "
                    + "inventory.carrier-rejected.")
    @PostMapping("/{id}/carrier-label")
    ShipmentResponse buyLabel(@PathVariable UUID id, @Valid @RequestBody BuyLabelRequest body) {
        return view(inbound.buyLabel(id, new InboundCommands.BuyLabel(
                body.carrier(), body.service(), body.shipDate())));
    }

    @Operation(operationId = "shipInboundShipment", summary = "Mark the plan shipped",
            description = "Expected units become inbound at the FC (storefront: Coming soon). Boxes, when given, "
                    + "must hold every planned unit.")
    @PostMapping("/{id}/ship")
    ResponseEntity<ShipmentResponse> ship(@PathVariable UUID id,
                                          @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                          String ifMatch) {
        InboundShipment s = inbound.ship(id, version(ifMatch));
        return ResponseEntity.ok().eTag(etag(s)).body(view(s));
    }

    @Operation(operationId = "receiveInboundShipment", summary = "Receive at the FC against the plan "
            + "(inventory:receive)",
            description = "Lines by SKU or scanned AT-FN label; the seller's lots are created (damaged units as -DMG "
                    + "quarantine lots, holdReason for QC holds), the plan's inbound units are consumed and "
                    + "inventory.inbound.received is published (offers switches the listing to Fulfilled by "
                    + "AfroTransact). Unplanned SKUs are UNEXPECTED discrepancies. Idempotent per Idempotency-Key.")
    @PostMapping("/{id}/receipts")
    ReceiptResponse receive(@PathVariable UUID id,
                            @RequestHeader(ReservationController.IDEMPOTENCY_KEY) @NotBlank @Size(max = 160)
                            String idempotencyKey,
                            @Valid @RequestBody ReceiveRequest body) {
        InboundShipmentService.ReceiptOutcome o = inbound.receive(id, body.toCommand(), idempotencyKey);
        return InboundDtos.ReceiptResponse.of(o, inbound.shipTo(o.shipment()));
    }

    @Operation(operationId = "closeInboundShipment", summary = "Finish receiving (inventory:receive)",
            description = "Units that did not arrive stop counting as inbound and are reported SHORT; "
                    + "inventory.inbound.closed carries every discrepancy.")
    @PostMapping("/{id}/close")
    ShipmentResponse close(@PathVariable UUID id) {
        return view(inbound.close(id));
    }

    @Operation(operationId = "cancelInboundShipment", summary = "Cancel a plan or a shipment not yet received")
    @PostMapping("/{id}/cancel")
    ShipmentResponse cancel(@PathVariable UUID id) {
        return view(inbound.cancel(id));
    }

    private ShipmentResponse view(InboundShipment s) {
        return ShipmentResponse.of(s, inbound.shipTo(s));
    }

    private static String etag(InboundShipment s) {
        return "\"" + s.version() + "\"";
    }

    private static Long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return null;
        }
        String v = ifMatch.trim();
        v = (v.startsWith("W/") ? v.substring(2) : v).replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be the version from the ETag, e.g. \"3\"", e);
        }
    }
}
