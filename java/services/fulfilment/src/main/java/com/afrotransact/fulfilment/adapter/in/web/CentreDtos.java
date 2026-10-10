package com.afrotransact.fulfilment.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.fulfilment.domain.FulfilmentCentre;
import com.afrotransact.fulfilment.domain.FulfilmentTask;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.TaskLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Fulfilment centres and pick/pack tasks. */
final class CentreDtos {

    private CentreDtos() {
    }

    @Schema(name = "CentreRequest", description = "Create or update a fulfilment centre (id from the path)")
    record CentreRequest(@NotBlank @Size(max = 120) String name,
                         @Schema(description = "FULFILMENT_CENTRE | ORIGIN_HUB | WAREHOUSE") String kind,
                         @Schema(example = "America/Chicago") String timezone, @Size(max = 200) String addressLine,
                         String city, String state, String postalCode, @Size(min = 2, max = 2) String country,
                         Double lat, Double lon, boolean coldChain, Boolean active) {

        FulfilmentCentre toDomain(String id) {
            return new FulfilmentCentre(id, name, kind == null ? "FULFILMENT_CENTRE" : kind,
                    timezone == null ? "America/Chicago" : timezone, addressLine, city, state, postalCode,
                    country == null ? "US" : country, GeoPoint.of(lat, lon), coldChain, active == null || active);
        }
    }

    @Schema(name = "Centre")
    record CentreView(String id, String name, String kind, String timezone, String addressLine, String city,
                      String state, String postalCode, String country, Double lat, Double lon, boolean coldChain,
                      boolean active) {

        static CentreView of(FulfilmentCentre c) {
            return new CentreView(c.id(), c.name(), c.kind(), c.timezone(), c.addressLine(), c.city(), c.state(),
                    c.postalCode(), c.country(), c.location() == null ? null : c.location().lat(),
                    c.location() == null ? null : c.location().lon(), c.coldChain(), c.active());
        }
    }

    @Schema(name = "FulfilmentTask", description = "Pick and pack of one FBA / Retail 1P shipment")
    record TaskView(String id, String shipmentId, String orderId, String orderNumber, String fulfilmentType,
                    String deliveryMethod, String nodeId,
                    @Schema(description = "PICK_PENDING | PICKING | PICK_EXCEPTION | PICKED | PACKED | READY | "
                            + "CANCELLED") String status,
                    List<TaskLine> lines,
                    @Schema(description = "AfroTransact package label (ATS + 9), scanned at driver load-out")
                    String packageLabel, Double weightLb, String carrier, String trackingNumber,
                    String exceptionReason, String operator, Instant updatedAt, int version) {

        static TaskView of(FulfilmentTask t) {
            return new TaskView(t.id(), t.shipmentId(), t.orderId(), t.orderNumber(), t.fulfilmentType(),
                    t.deliveryMethod(), t.nodeId(), t.status().name(), t.lines(), t.packageLabel(), t.weightLb(),
                    t.carrier(), t.trackingNumber(), t.exceptionReason(), t.operator(), t.updatedAt(), t.version());
        }
    }

    @Schema(name = "AssignNodeRequest")
    record AssignNodeRequest(@NotBlank String nodeId) {
    }

    @Schema(name = "PickRequest", description = "Units picked per variant ATIN; fewer than ordered needs shortReason")
    record PickRequest(Map<String, Integer> picked,
                       @Schema(description = "Lot ids picked from per variant ATIN (comma separated)")
                       Map<String, String> lots, @Size(max = 500) String shortReason) {
    }

    @Schema(name = "PackRequest", description = "Carrier shipments need the label bought from carrier-gateway")
    record PackRequest(@DecimalMin(value = "0.01") @DecimalMax(value = "150") double weightLb, String carrier,
                       String trackingNumber) {
    }
}
