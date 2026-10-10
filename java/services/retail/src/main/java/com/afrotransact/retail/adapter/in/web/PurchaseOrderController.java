package com.afrotransact.retail.adapter.in.web;

import java.util.UUID;

import com.afrotransact.retail.adapter.in.web.PurchaseOrderDtos.CreatePoRequest;
import com.afrotransact.retail.adapter.in.web.PurchaseOrderDtos.LinesRequest;
import com.afrotransact.retail.adapter.in.web.PurchaseOrderDtos.NoteRequest;
import com.afrotransact.retail.adapter.in.web.PurchaseOrderDtos.PoResponse;
import com.afrotransact.retail.application.PurchaseOrderService;
import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PurchaseOrder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

@Tag(name = "Purchase orders", description = "Draft → Pending approval → Approved → Sent → Confirmed → (Partially) "
        + "received → Closed, or Cancelled until a shipment is booked; FX captured at the PO date (S070). "
        + "Writes accept If-Match with the version from the ETag.")
@RestController
@RequestMapping("/v1/purchase-orders")
class PurchaseOrderController {

    private final PurchaseOrderService orders;

    PurchaseOrderController(PurchaseOrderService orders) {
        this.orders = orders;
    }

    @Operation(operationId = "createPurchaseOrder", summary = "Raise a draft PO (retail:po:write); supplier must be APPROVED or ON_WATCH")
    @PostMapping
    ResponseEntity<PoResponse> create(@Valid @RequestBody CreatePoRequest body) {
        PurchaseOrder po = orders.create(body.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).eTag("\"" + po.version() + "\"").body(PoResponse.of(po));
    }

    @Operation(operationId = "listPurchaseOrders", summary = "List POs (retail:read)")
    @GetMapping
    PageResponse<PoResponse> list(@RequestParam(required = false) PoStatus status,
                                  @RequestParam(required = false) UUID supplierId,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(orders.list(status, supplierId, page, size), PoResponse::of);
    }

    @Operation(operationId = "getPurchaseOrder", summary = "A PO with lines, received/invoiced quantities and history (retail:read)")
    @GetMapping("/{id}")
    ResponseEntity<PoResponse> get(@PathVariable UUID id) {
        return tagged(orders.get(id));
    }

    @Operation(operationId = "replacePurchaseOrderLines", summary = "Replace the lines of a draft PO (retail:po:write)")
    @PutMapping("/{id}/lines")
    ResponseEntity<PoResponse> lines(@PathVariable UUID id, @Valid @RequestBody LinesRequest body,
                                     @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(orders.replaceLines(id, body.toInputs(), Versions.expected(ifMatch)));
    }

    @Operation(operationId = "submitPurchaseOrder", summary = "Submit for approval (retail:po:write)")
    @PostMapping("/{id}/submit")
    ResponseEntity<PoResponse> submit(@PathVariable UUID id,
                                      @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(orders.submit(id, Versions.expected(ifMatch)));
    }

    @Operation(operationId = "approvePurchaseOrder", summary = "Approve (retail:po:approve; above the Finance threshold also retail:finance)")
    @PostMapping("/{id}/approve")
    ResponseEntity<PoResponse> approve(@PathVariable UUID id, @Valid @RequestBody(required = false) NoteRequest body,
                                       @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(orders.approve(id, body == null ? null : body.note(), Versions.expected(ifMatch)));
    }

    @Operation(operationId = "sendBackPurchaseOrder", summary = "Send back to the buyer with a note (retail:po:approve)")
    @PostMapping("/{id}/send-back")
    ResponseEntity<PoResponse> sendBack(@PathVariable UUID id, @Valid @RequestBody NoteRequest body,
                                        @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(orders.sendBack(id, body.note(), Versions.expected(ifMatch)));
    }

    @Operation(operationId = "sendPurchaseOrder", summary = "Send to the supplier: PO PDF + EDI 850 (retail:po:write)",
            description = "Queues the delivery to the supplier (S225): the PO PDF is stored in media and the supplier "
                    + "contact is emailed a signed link through notifications; EDI-enabled suppliers also get the "
                    + "X12 850 through the EDI transport. Track it with GET /{id}/dispatches.")
    @PostMapping("/{id}/send")
    ResponseEntity<PoResponse> send(@PathVariable UUID id,
                                    @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(orders.send(id, Versions.expected(ifMatch)));
    }

    @Operation(operationId = "confirmPurchaseOrder", summary = "Record the supplier's confirmation (retail:po:write)")
    @PostMapping("/{id}/confirm")
    ResponseEntity<PoResponse> confirm(@PathVariable UUID id, @Valid @RequestBody(required = false) NoteRequest body,
                                       @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(orders.confirm(id, body == null ? null : body.note(), Versions.expected(ifMatch)));
    }

    @Operation(operationId = "cancelPurchaseOrder", summary = "Cancel with a reason (retail:po:write); only until a shipment is booked")
    @PostMapping("/{id}/cancel")
    ResponseEntity<PoResponse> cancel(@PathVariable UUID id, @Valid @RequestBody NoteRequest body,
                                      @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(orders.cancel(id, body.note(), Versions.expected(ifMatch)));
    }

    @Operation(operationId = "closePurchaseOrder", summary = "Close a received, fully matched PO and release supplier payment (retail:finance)")
    @PostMapping("/{id}/close")
    ResponseEntity<PoResponse> close(@PathVariable UUID id,
                                     @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(orders.close(id, Versions.expected(ifMatch)));
    }

    @Operation(operationId = "getPurchaseOrderEdi850", summary = "The ANSI X12 850 sent to the supplier (approved POs)")
    @GetMapping(path = "/{id}/edi850", produces = MediaType.TEXT_PLAIN_VALUE)
    String edi850(@PathVariable UUID id) {
        return orders.edi850(id);
    }

    private static ResponseEntity<PoResponse> tagged(PurchaseOrder po) {
        return Versions.tagged(PoResponse.of(po), po.version());
    }
}
