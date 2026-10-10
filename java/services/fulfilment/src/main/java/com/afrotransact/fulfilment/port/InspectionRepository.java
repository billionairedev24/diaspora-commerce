package com.afrotransact.fulfilment.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.InspectionOutcome;
import com.afrotransact.fulfilment.domain.InspectionReport;

/** Vehicle inspections and defects reported to dispatch. */
public interface InspectionRepository {

    /** @return the inspection id */
    String save(String eventId, String routeId, String driverId, InspectionReport report, InspectionOutcome outcome,
                Instant recordedAt);

    /** Highest odometer recorded for a vehicle (start or end of a shift). */
    Optional<Integer> lastOdometer(String vehicleId);

    void recordEndOdometer(String vehicleId, String routeId, int odometer, Instant at);

    String reportDefect(String inspectionId, String routeId, String vehicleId, String driverId, boolean critical,
                        List<String> items, String note, Instant at);

    List<Defect> openDefects();

    /** @return true when the defect was open */
    boolean resolveDefect(String defectId, String by, String note, Instant at);

    /** A defect for the dispatch console. */
    record Defect(String id, String routeId, String vehicleId, String driverId, boolean critical, List<String> items,
                  String note, Instant reportedAt) {
    }
}
