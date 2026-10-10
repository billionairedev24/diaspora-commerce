package com.afrotransact.retail.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.adapter.in.web.ShipmentDtos.AsnRequest;
import com.afrotransact.retail.adapter.in.web.ShipmentDtos.BookShipmentRequest;
import com.afrotransact.retail.adapter.in.web.ShipmentDtos.ChargesRequest;
import com.afrotransact.retail.adapter.in.web.ShipmentDtos.DocumentRequest;
import com.afrotransact.retail.adapter.in.web.ShipmentDtos.ShipmentResponse;
import com.afrotransact.retail.application.ShipmentService;
import com.afrotransact.retail.domain.InboundShipment;
import com.afrotransact.retail.domain.ShipmentStage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

@Tag(name = "Inbound shipments", description = "Shipments and ASNs against POs: packing-list batches and cartons, "
        + "customs documents, nine-step timeline, landed-cost charges (S071)")
@RestController
@RequestMapping("/v1/shipments")
class ShipmentController {

    private final ShipmentService shipments;

    ShipmentController(ShipmentService shipments) {
        this.shipments = shipments;
    }

    @Operation(operationId = "bookShipment", summary = "Book a shipment for a sent/confirmed PO (retail:logistics); creates the ASN")
    @PostMapping
    ResponseEntity<ShipmentResponse> book(@Valid @RequestBody BookShipmentRequest body) {
        InboundShipment s = shipments.book(body.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).eTag("\"" + s.version() + "\"")
                .body(ShipmentResponse.of(s));
    }

    @Operation(operationId = "listShipments", summary = "List shipments (retail:read)")
    @GetMapping
    PageResponse<ShipmentResponse> list(@RequestParam(required = false) ShipmentStage stage,
                                        @RequestParam(required = false) UUID poId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(shipments.list(stage, poId, page, size), ShipmentResponse::of);
    }

    @Operation(operationId = "getShipment", summary = "A shipment with ASN, documents and milestones (retail:read)")
    @GetMapping("/{id}")
    ResponseEntity<ShipmentResponse> get(@PathVariable UUID id) {
        return tagged(shipments.get(id));
    }

    @Operation(operationId = "updateShipmentAsn", summary = "Replace ASN lines and cartons (retail:logistics); locked after departure")
    @PutMapping("/{id}/asn")
    ResponseEntity<ShipmentResponse> asn(@PathVariable UUID id, @Valid @RequestBody AsnRequest body,
                                         @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(shipments.updateAsn(id, body.toInputs(), body.toCartons(), Versions.expected(ifMatch)));
    }

    @Operation(operationId = "setShipmentDocument", summary = "Set a customs/shipping document status (retail:logistics)")
    @PostMapping("/{id}/documents")
    ResponseEntity<ShipmentResponse> document(@PathVariable UUID id, @Valid @RequestBody DocumentRequest body) {
        return tagged(shipments.setDocument(id, body.name(), body.status(), body.reference(), body.note()));
    }

    @Operation(operationId = "updateShipmentCharges", summary = "Replace landed-cost charges: freight, insurance, duty, brokerage, drayage, fees, receiving")
    @PutMapping("/{id}/charges")
    ResponseEntity<ShipmentResponse> charges(@PathVariable UUID id, @Valid @RequestBody ChargesRequest body,
                                             @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(shipments.updateCharges(id, body.charges() == null ? List.of()
                : body.charges().stream().map(ChargeDto::toDomain).toList(), Versions.expected(ifMatch)));
    }

    @Operation(operationId = "advanceShipment", summary = "Advance to the next milestone (retail:logistics)",
            description = "Departure is blocked while documents are MISSING and posts Dr 1310 / Cr 2110; customs "
                    + "clearance is blocked by holds. The last step is reached by completing the goods receipt.")
    @PostMapping("/{id}/advance")
    ResponseEntity<ShipmentResponse> advance(@PathVariable UUID id,
                                             @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(shipments.advance(id, Versions.expected(ifMatch)));
    }

    @Operation(operationId = "getShipmentLandedCost", summary = "Estimated landed cost of the ASN per line and per unit (retail:read)")
    @GetMapping("/{id}/landed-cost")
    LandedCostResponse landedCost(@PathVariable UUID id) {
        return LandedCostResponse.of(shipments.landedCostEstimate(id));
    }

    private static ResponseEntity<ShipmentResponse> tagged(InboundShipment s) {
        return Versions.tagged(ShipmentResponse.of(s), s.version());
    }
}
