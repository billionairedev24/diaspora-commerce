package com.afrotransact.inventory.adapter.in.web;

import com.afrotransact.inventory.adapter.in.web.InventoryDtos.CommitResponse;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.ReleaseRequest;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.ReservationResponse;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.ReserveRequest;
import com.afrotransact.inventory.application.ReservationResult;
import com.afrotransact.inventory.application.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reservations", description = "Checkout holds: reserve → confirm → commit (ship) or release. "
        + "Called by the orders service (SERVICE principal with inventory:reserve).")
@RestController
@RequestMapping("/v1/reservations")
class ReservationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ReservationService reservations;

    ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Operation(summary = "Reserve all lines of an order, or none",
            description = "Locks positions in (sku, node) order, allocates lots FEFO and holds stock for the "
                    + "reservation TTL (15 min). The order id is the idempotency key: a retry returns the existing "
                    + "reservation with 200 and replayed=true and never reserves twice.")
    @ApiResponse(responseCode = "201", description = "Reserved")
    @ApiResponse(responseCode = "200", description = "Replay of an earlier reservation for this order")
    @ApiResponse(responseCode = "409", description = "inventory.insufficient-stock with per-SKU shortfalls",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    ResponseEntity<ReservationResponse> reserve(
            @Parameter(description = "Unique per attempt; replays return the stored result")
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody ReserveRequest body) {
        ReservationResult result = reservations.reserve(body.toCommand(idempotencyKey));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ReservationResponse.of(result.reservation(), result.replayed()));
    }

    @Operation(summary = "Get the reservation of an order (orders service or staff)")
    @GetMapping("/{orderId}")
    ReservationResponse get(@PathVariable String orderId) {
        return ReservationResponse.of(reservations.get(orderId), false);
    }

    @Operation(summary = "Payment authorised: keep the hold until shipment (no more TTL)")
    @PostMapping("/{orderId}/confirm")
    ReservationResponse confirm(@PathVariable String orderId,
                                @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey) {
        return ReservationResponse.of(reservations.confirm(orderId), false);
    }

    @Operation(summary = "Ship the order: SHIP movements, lot decrements and COGS at lot landed cost",
            description = "Idempotent: committing a committed reservation returns the same result with replayed=true.")
    @PostMapping("/{orderId}/commit")
    CommitResponse commit(@PathVariable String orderId,
                          @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey) {
        return CommitResponse.of(reservations.commit(orderId));
    }

    @Operation(summary = "Release a held reservation (cancellation or payment failure)",
            description = "Idempotent: releasing a released or expired reservation returns it unchanged.")
    @PostMapping("/{orderId}/release")
    ReservationResponse release(@PathVariable String orderId,
                                @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
                                @Valid @RequestBody(required = false) ReleaseRequest body) {
        return ReservationResponse.of(reservations.release(orderId, body == null ? null : body.reason()), false);
    }
}
