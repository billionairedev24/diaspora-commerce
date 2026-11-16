package com.afrotransact.orders.adapter.in.web;

import java.util.Arrays;
import java.util.List;

import com.afrotransact.orders.application.DeliveryDeskService;
import com.afrotransact.orders.application.DeliveryDeskService.Command;
import com.afrotransact.orders.application.DeliveryDeskService.ProofInput;
import com.afrotransact.platform.security.StaffOrServiceOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Delivery desk", description = "Staff run AfroTransact-delivered shipments end to end without the driver "
        + "app: queue, take or assign, picked up, out for delivery, delivered with proof, failed and reattempt, "
        + "proof correction. delivery:read to view, delivery:manage to act; every action is audited, idempotent per "
        + "Idempotency-Key and optimistic-locked on the job version")
@RestController
@StaffOrServiceOnly
class DeliveryDeskController {

    private final DeliveryDeskService desk;

    DeliveryDeskController(DeliveryDeskService desk) {
        this.desk = desk;
    }

    @Operation(summary = "The delivery queue (delivery:read)",
            description = "Shipments AfroTransact delivers: Fulfilled by AfroTransact, and sellers who opted into "
                    + "delivery by AfroTransact. Seller-shipped orders and pickups are never listed. Default statuses: "
                    + "ready, picked_up, in_transit, out_for_delivery, delivery_failed. Names and cities, no ids.")
    @GetMapping("/v1/deliveries")
    DeliveryDeskService.DeskPage list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String mode,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String assignee,
                                      @RequestParam(required = false) String city,
                                      @RequestParam(required = false) String seller,
                                      @RequestParam(required = false) String sort,
                                      @RequestParam(defaultValue = "false") boolean desc,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "25") int size) {
        List<String> statuses = status == null || status.isBlank() ? null
                : Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return desk.list(new DeliveryDeskService.Query(statuses, mode, q, assignee, city, seller, sort, desc), page,
                size);
    }

    @Operation(summary = "Staff who can be assigned a delivery (delivery:read)")
    @GetMapping("/v1/deliveries/couriers")
    List<DeliveryDeskService.Courier> couriers() {
        return desk.couriers();
    }

    @Operation(summary = "One delivery with its job, proof and audit trail (delivery:read)",
            description = "The street address, phone, coordinates and audit details need delivery:manage; readers "
                    + "see the name and city.")
    @GetMapping("/v1/deliveries/{shipmentId}")
    DeliveryDeskService.Detail get(@PathVariable String shipmentId) {
        return desk.get(shipmentId);
    }

    @Operation(summary = "Take the delivery (delivery:manage)")
    @PostMapping("/v1/deliveries/{shipmentId}/take")
    DeliveryDeskService.Detail take(@PathVariable String shipmentId, @RequestHeader(value = "Idempotency-Key",
            required = false) String key, @Valid @RequestBody(required = false) ActionRequest body) {
        return desk.take(shipmentId, command(key, body));
    }

    @Operation(summary = "Assign the delivery to a named staff member or courier (delivery:manage)",
            description = "The assignee must hold delivery:manage; their name is read from identity, never trusted "
                    + "from the request.")
    @PostMapping("/v1/deliveries/{shipmentId}/assign")
    DeliveryDeskService.Detail assign(@PathVariable String shipmentId, @RequestHeader(value = "Idempotency-Key",
            required = false) String key, @Valid @RequestBody AssignRequest body) {
        return desk.assign(shipmentId, body.assigneeId(), command(key, body));
    }

    @Operation(summary = "Mark picked up from the seller or the warehouse (delivery:manage)")
    @PostMapping("/v1/deliveries/{shipmentId}/picked-up")
    DeliveryDeskService.Detail pickedUp(@PathVariable String shipmentId, @RequestHeader(value = "Idempotency-Key",
            required = false) String key, @Valid @RequestBody(required = false) ActionRequest body) {
        return desk.pickedUp(shipmentId, command(key, body));
    }

    @Operation(summary = "Mark in transit, an optional step between picked up and out for delivery (delivery:manage)")
    @PostMapping("/v1/deliveries/{shipmentId}/in-transit")
    DeliveryDeskService.Detail inTransit(@PathVariable String shipmentId, @RequestHeader(value = "Idempotency-Key",
            required = false) String key, @Valid @RequestBody(required = false) ActionRequest body) {
        return desk.inTransit(shipmentId, command(key, body));
    }

    @Operation(summary = "Mark out for delivery, or reattempt after a failed delivery (delivery:manage)",
            description = "Notifies the customer.")
    @PostMapping("/v1/deliveries/{shipmentId}/out-for-delivery")
    DeliveryDeskService.Detail outForDelivery(@PathVariable String shipmentId, @RequestHeader(
            value = "Idempotency-Key", required = false) String key,
                                              @Valid @RequestBody(required = false) ActionRequest body) {
        return desk.outForDelivery(shipmentId, command(key, body));
    }

    @Operation(summary = "Mark delivered with proof (delivery:manage)",
            description = "At least one photo (media asset of purpose proof-photo); optional recipient name, note and "
                    + "coordinates. 422 orders.proof-photo-required without a photo. The proof is saved once; a "
                    + "second delivered call changes nothing. Notifies the customer.")
    @PostMapping("/v1/deliveries/{shipmentId}/delivered")
    DeliveryDeskService.Detail delivered(@PathVariable String shipmentId, @RequestHeader(value = "Idempotency-Key",
            required = false) String key, @Valid @RequestBody ProofRequest body) {
        return desk.delivered(shipmentId, body.toInput(), command(key, body));
    }

    @Operation(summary = "Record a failed delivery attempt with a reason (delivery:manage)")
    @PostMapping("/v1/deliveries/{shipmentId}/failed")
    DeliveryDeskService.Detail failed(@PathVariable String shipmentId, @RequestHeader(value = "Idempotency-Key",
            required = false) String key, @Valid @RequestBody FailedRequest body) {
        return desk.failed(shipmentId, body.reason(), command(key, body));
    }

    @Operation(summary = "Return a parcel to the seller after a failed delivery (delivery:manage)",
            description = "Only from delivery failed, with a reason that is kept in the audit trail.")
    @PostMapping("/v1/deliveries/{shipmentId}/returned")
    DeliveryDeskService.Detail returned(@PathVariable String shipmentId, @RequestHeader(value = "Idempotency-Key",
            required = false) String key, @Valid @RequestBody FailedRequest body) {
        return desk.returned(shipmentId, body.reason(), command(key, body));
    }

    @Operation(summary = "Correct a saved proof of delivery (delivery:manage)",
            description = "The only way a proof changes: a reason is required and the old values are kept in the "
                    + "audit trail.")
    @PutMapping("/v1/deliveries/{shipmentId}/proof")
    DeliveryDeskService.Detail correctProof(@PathVariable String shipmentId, @RequestHeader(
            value = "Idempotency-Key", required = false) String key, @Valid @RequestBody CorrectionRequest body) {
        return desk.correctProof(shipmentId, body.toInput(), body.reason(), command(key, body));
    }

    private static Command command(String key, Versioned body) {
        return new Command(body == null ? null : body.version(), key, body == null ? null : body.note(),
                body == null ? null : body.expectedStatus());
    }

    /** A request that carries the job version the caller saw and a note. */
    interface Versioned {

        Long version();

        String note();

        /** The shipment status the caller saw (optional): another person's earlier change is reported, not overruled. */
        String expectedStatus();
    }

    record ActionRequest(Long version, @Size(max = 500) String note, String expectedStatus) implements Versioned {
    }

    record AssignRequest(String assigneeId, Long version, @Size(max = 500) String note, String expectedStatus)
            implements Versioned {
    }

    record FailedRequest(String reason, Long version, @Size(max = 500) String note, String expectedStatus)
            implements Versioned {
    }

    record ProofRequest(List<String> photoAssetIds, @Size(max = 120) String recipientName,
                        @Size(max = 500) String note, Double latitude, Double longitude, Long version,
                        String expectedStatus) implements Versioned {

        ProofInput toInput() {
            return new ProofInput(photoAssetIds, recipientName, note, latitude, longitude);
        }
    }

    record CorrectionRequest(List<String> photoAssetIds, @Size(max = 120) String recipientName,
                             @Size(max = 500) String note, Double latitude, Double longitude, String reason,
                             Long version, String expectedStatus) implements Versioned {

        ProofInput toInput() {
            return new ProofInput(photoAssetIds, recipientName, note, latitude, longitude);
        }
    }
}
