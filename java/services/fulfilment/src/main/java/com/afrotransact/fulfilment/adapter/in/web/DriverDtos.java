package com.afrotransact.fulfilment.adapter.in.web;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.fulfilment.application.DriverEvent;
import com.afrotransact.fulfilment.domain.CaptureSource;
import com.afrotransact.fulfilment.domain.CheckResult;
import com.afrotransact.fulfilment.domain.ContactKind;
import com.afrotransact.fulfilment.domain.DeliveryProblem;
import com.afrotransact.fulfilment.domain.DeliveryProblemType;
import com.afrotransact.fulfilment.domain.HandoverMethod;
import com.afrotransact.fulfilment.domain.InspectionItem;
import com.afrotransact.fulfilment.domain.InspectionReport;
import com.afrotransact.fulfilment.domain.PhotoCapture;
import com.afrotransact.fulfilment.domain.PhotoSlot;
import com.afrotransact.fulfilment.domain.PickupProblem;
import com.afrotransact.fulfilment.domain.PickupProblemType;
import com.afrotransact.fulfilment.domain.PickupResolution;
import com.afrotransact.fulfilment.domain.ProblemResolution;
import com.afrotransact.fulfilment.domain.ProofOfDelivery;
import com.afrotransact.fulfilment.domain.SosKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Driver app payloads. Event bodies are parsed leniently: an unknown enum value becomes null and the event is
 * REJECTED with the rule that failed, so one bad event never blocks the rest of the offline queue.
 */
final class DriverDtos {

    private DriverDtos() {
    }

    @Schema(name = "SyncRequest", description = "A batch of queued driver events from one device")
    record SyncRequest(@NotBlank @Size(max = 120) String deviceId,
                       @NotNull @Size(max = 200) List<DriverEventRequest> events) {
    }

    @Schema(name = "SosRequest", description = "Immediate SOS (also queue it with the same eventId)")
    record SosRequest(@NotBlank @Size(max = 120) String deviceId, @NotNull DriverEventRequest event) {
    }

    @Schema(name = "DriverEvent", description = "One queued driver action. Exactly the payload object matching the "
            + "type is read: inspection.submitted → inspection; loadout.scanned / stop.scanned / return.scanned → "
            + "scan; pickup.confirmed → pickup; pickup.problem → pickupProblem; customer.contacted → contact; "
            + "delivery.completed → delivery; delivery.problem → problem; shift.ended → odometer; sos.raised → sos")
    record DriverEventRequest(
            @Schema(description = "Client-generated unique id (idempotency key), e.g. a UUID") @Size(max = 80)
            String eventId,
            @Schema(description = "Per-device sequence number starting at 1; applied strictly in order") long sequence,
            @Schema(description = "session.signed_in | inspection.submitted | loadout.scanned | loadout.completed | "
                    + "route.started | stop.arrived | stop.scanned | pickup.confirmed | pickup.problem | "
                    + "customer.contacted | delivery.completed | delivery.problem | return.scanned | shift.ended | "
                    + "sos.raised") String type,
            String routeId, String stopId,
            @Schema(description = "Device time of the action") Instant capturedAt, Double lat, Double lon,
            @Size(max = 1000) String note, InspectionPayload inspection, ScanPayload scan, PickupPayload pickup,
            PickupProblemPayload pickupProblem,
            @Schema(description = "CALL | TEXT | WAIT_STARTED") String contact, DeliveryPayload delivery,
            ProblemPayload problem, @Schema(description = "End-of-shift odometer (mi)") Integer odometer,
            @Schema(description = "BREAKDOWN | ACCIDENT | UNSAFE | MEDICAL") String sos) {

        DriverEvent toEvent() {
            return new DriverEvent(eventId, sequence, type, routeId, stopId, capturedAt, lat, lon, note,
                    inspection == null ? null : inspection.toDomain(capturedAt), scan == null ? null
                    : new DriverEvent.Scan(scan.label(), "MANUAL".equalsIgnoreCase(scan.method())),
                    pickup == null ? null : new DriverEvent.PickupConfirmation(pickup.handedOverBy(),
                            pickup.missingReason()),
                    pickupProblem == null ? null : pickupProblem.toDomain(capturedAt),
                    Enums.lenient(ContactKind.class, contact), delivery == null ? null : delivery.toDomain(),
                    problem == null ? null : problem.toDomain(capturedAt), odometer,
                    Enums.lenient(SosKind.class, sos));
        }
    }

    @Schema(name = "PhotoCapture", description = "A photo from the live in-app camera, uploaded to media-service")
    record PhotoPayload(@Schema(description = "media-service asset id") String assetId,
                        @Schema(description = "LIVE_CAMERA (only accepted value) | GALLERY") String source,
                        Instant capturedAt, Double lat, Double lon, Double accuracyM,
                        @Schema(description = "Route id burnt into the stamp") String stampRouteId,
                        @Schema(description = "SHA-256 of the image bytes, computed on the device") String sha256) {

        PhotoCapture toDomain() {
            CaptureSource s = Enums.lenient(CaptureSource.class, source);
            return new PhotoCapture(assetId, s == null ? CaptureSource.UNKNOWN : s, capturedAt, lat, lon, accuracyM,
                    stampRouteId, sha256);
        }

        static PhotoCapture of(PhotoPayload p) {
            return p == null ? null : p.toDomain();
        }
    }

    @Schema(name = "InspectionPayload", description = "Pre-trip inspection (DVIR)")
    record InspectionPayload(String vehicleId,
                             @Schema(description = "PASS | FAIL per item: TYRES, BRAKES, LIGHTS (critical), MIRRORS, "
                                     + "HORN_WIPERS, NO_LEAKS, CARGO_AREA, COLD_BOX, SAFETY_KIT, PHONE_MOUNT")
                             Map<String, String> checks, Integer odometerMiles, Integer fuelPercent, Double coldBoxF,
                             @Schema(description = "FRONT, BACK, LEFT, RIGHT") Map<String, PhotoPayload> photos,
                             String defectNote, boolean certified, String signatureAssetId) {

        InspectionReport toDomain(Instant at) {
            Map<InspectionItem, CheckResult> results = new EnumMap<>(InspectionItem.class);
            if (checks != null) {
                checks.forEach((k, v) -> {
                    InspectionItem item = Enums.lenient(InspectionItem.class, k);
                    CheckResult r = Enums.lenient(CheckResult.class, v);
                    if (item != null && r != null) {
                        results.put(item, r);
                    }
                });
            }
            Map<PhotoSlot, PhotoCapture> shots = new EnumMap<>(PhotoSlot.class);
            if (photos != null) {
                photos.forEach((k, v) -> {
                    PhotoSlot slot = Enums.lenient(PhotoSlot.class, k);
                    if (slot != null && v != null) {
                        shots.put(slot, v.toDomain());
                    }
                });
            }
            return new InspectionReport(vehicleId, results, odometerMiles, fuelPercent, coldBoxF, shots, defectNote,
                    certified, signatureAssetId, at);
        }
    }

    @Schema(name = "ScanPayload")
    record ScanPayload(@Schema(description = "ATS + 9 characters") String label,
                       @Schema(description = "CAMERA | MANUAL (typed in; flagged)") String method) {
    }

    @Schema(name = "PickupPayload", description = "Seller pickup confirmation")
    record PickupPayload(@Schema(description = "Seller staff who handed over") String handedOverBy,
                         @Schema(description = "Required when not every expected label was scanned")
                         String missingReason) {
    }

    @Schema(name = "PickupProblemPayload")
    record PickupProblemPayload(@Schema(description = "NOT_READY | CLOSED | WRONG_COUNT | DAMAGED") String type,
                                @Schema(description = "WAIT | SKIP | PARTIAL") String resolution, String note,
                                PhotoPayload photo) {

        PickupProblem toDomain(Instant at) {
            return new PickupProblem(Enums.lenient(PickupProblemType.class, type),
                    Enums.lenient(PickupResolution.class, resolution), note, PhotoPayload.of(photo), at);
        }
    }

    @Schema(name = "DeliveryPayload", description = "Proof of delivery")
    record DeliveryPayload(@Schema(description = "CUSTOMER | SOMEONE_ELSE | SAFE_PLACE") String handoverMethod,
                           @Schema(description = "Customer's 4-digit code (checked by orders-service, never stored)")
                           String handoverCode, String recipientName,
                           @Schema(description = "Family member, Neighbour, Roommate, Front desk / concierge, "
                                   + "Colleague") String relationship,
                           String signatureAssetId, String safePlace,
                           @Schema(description = "Stamped with time, GPS and route id; within 150 m of the address")
                           PhotoPayload photo) {

        ProofOfDelivery toDomain() {
            return new ProofOfDelivery(Enums.lenient(HandoverMethod.class, handoverMethod), handoverCode,
                    recipientName, relationship, signatureAssetId, safePlace, PhotoPayload.of(photo));
        }
    }

    @Schema(name = "ProblemPayload", description = "Delivery problem")
    record ProblemPayload(@Schema(description = "CUSTOMER_UNAVAILABLE | ACCESS_ISSUE | DAMAGED | REFUSED | MISSING "
                                  + "| UNSAFE") String type,
                          @Schema(description = "REATTEMPT | PICKUP_POINT | RETURN_TO_FC | ACCEPTED_WITH_DAMAGE | "
                                  + "REPORT_MISSING") String resolution,
                          String note, PhotoPayload photo) {

        DeliveryProblem toDomain(Instant at) {
            return new DeliveryProblem(Enums.lenient(DeliveryProblemType.class, type),
                    Enums.lenient(ProblemResolution.class, resolution), note, PhotoPayload.of(photo), at);
        }
    }

    @Schema(name = "DriverSession", description = "Access check after sign-in")
    record SessionView(String driverId, String access, List<String> permissions, List<String> routeIds,
                       Instant checkedAt) {
    }
}
