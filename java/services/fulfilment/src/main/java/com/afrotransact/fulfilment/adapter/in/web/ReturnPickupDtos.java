package com.afrotransact.fulfilment.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.fulfilment.application.ScheduleReturnPickupCommand;
import com.afrotransact.fulfilment.domain.ReturnPickup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Bodies of the return pickup endpoints. */
final class ReturnPickupDtos {

    private ReturnPickupDtos() {
    }

    @Schema(name = "ReturnPickupAddress", description = "The customer's address (from the order)")
    record AddressDto(@Size(max = 200) String name, @Size(max = 40) String phone,
                      @NotBlank @Size(max = 200) String line1, @Size(max = 200) String line2,
                      @Size(max = 100) String city, @Size(max = 60) String state,
                      @NotBlank @Size(max = 20) String postalCode, @Size(max = 2) String country, Double lat,
                      Double lon) {
    }

    @Schema(name = "ScheduleReturnPickupRequest", description = "A DRIVER_PICKUP return to collect (one per RMA)")
    record ScheduleRequest(@NotBlank @Size(max = 64) String rmaId, @NotBlank @Size(max = 40) String rmaNumber,
                           @Size(max = 64) String orderId, @Size(max = 40) String orderNumber,
                           @Size(max = 64) String shipmentId, @Size(max = 120) String customerId,
                           @Size(max = 80) String sellerOrgId, @NotNull @Valid AddressDto address,
                           @Schema(description = "FC the parcel goes back to (return centre); default node otherwise")
                           @Size(max = 20) String depotNodeId,
                           @Schema(description = "First day the customer is ready; default today") LocalDate readyFrom,
                           @Schema(description = "Last day of the pickup window") LocalDate collectBy,
                           @Min(1) @Max(999) int units) {

        ScheduleReturnPickupCommand toCommand() {
            return new ScheduleReturnPickupCommand(rmaId, rmaNumber, orderId, orderNumber, shipmentId, customerId,
                    sellerOrgId, new ScheduleReturnPickupCommand.Address(address.name(), address.phone(),
                    address.line1(), address.line2(), address.city(), address.state(), address.postalCode(),
                    address.country(), address.lat(), address.lon()), depotNodeId, readyFrom, collectBy, units);
        }
    }

    @Schema(name = "AddReturnPickupsRequest", description = "Return pickups to add to a route as RETURN_PICKUP stops")
    record AddRequest(@NotEmpty @Size(max = 50) List<@NotBlank String> returnPickupIds) {
    }

    @Schema(name = "CancelReturnPickupRequest")
    record CancelRequest(@Size(max = 500) String reason) {
    }

    @Schema(name = "ReturnPickup")
    record ReturnPickupView(String id, String rmaId, String rmaNumber, String orderId, String orderNumber,
                            String shipmentId, String customerId, String sellerOrgId, String name, String addressLine,
                            String city, String state, String postalCode, String country, String depotNodeId,
                            LocalDate readyFrom, LocalDate collectBy, int units,
                            @Schema(description = "REQUESTED | ROUTED | COLLECTED | FAILED | CANCELLED") String status,
                            String routeId, String stopId, String note, Instant createdAt, Instant updatedAt) {

        static ReturnPickupView of(ReturnPickup p) {
            return new ReturnPickupView(p.id(), p.rmaId(), p.rmaNumber(), p.orderId(), p.orderNumber(),
                    p.shipmentId(), p.customerId(), p.sellerOrgId(), p.name(), p.addressLine(), p.city(), p.state(),
                    p.postalCode(), p.country(), p.depotNodeId(), p.readyFrom(), p.collectBy(), p.units(),
                    p.status().name(), p.routeId(), p.stopId(), p.note(), p.createdAt(), p.updatedAt());
        }
    }
}
