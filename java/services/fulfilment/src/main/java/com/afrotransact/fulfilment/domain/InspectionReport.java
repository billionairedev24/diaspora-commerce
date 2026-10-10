package com.afrotransact.fulfilment.domain;

import java.time.Instant;
import java.util.Map;

/**
 * What the driver submits before load-out.
 *
 * @param vehicleId          vehicle inspected
 * @param checks             result per item (all ten required)
 * @param odometerMiles      odometer reading
 * @param fuelPercent        fuel or charge, 0–100
 * @param coldBoxF           cold-box temperature in °F (required with perishables on board)
 * @param photos             front, back, left and right photos from the live camera
 * @param defectNote         description of failed items
 * @param certified          the driver certifies the inspection
 * @param signatureAssetId   media asset of the driver's signature
 * @param capturedAt         device time of the submission
 */
public record InspectionReport(String vehicleId, Map<InspectionItem, CheckResult> checks, Integer odometerMiles,
                               Integer fuelPercent, Double coldBoxF, Map<PhotoSlot, PhotoCapture> photos,
                               String defectNote, boolean certified, String signatureAssetId, Instant capturedAt) {

    public InspectionReport {
        checks = checks == null ? Map.of() : Map.copyOf(checks);
        photos = photos == null ? Map.of() : Map.copyOf(photos);
    }
}
