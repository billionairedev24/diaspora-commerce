package com.afrotransact.fulfilment.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.domain.InspectionOutcome;
import com.afrotransact.fulfilment.domain.InspectionReport;
import com.afrotransact.fulfilment.port.InspectionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInspectionRepository implements InspectionRepository {

    private final JdbcClient jdbc;
    private final Json json;

    JdbcInspectionRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** What is stored as the inspection document: the report plus the evaluated outcome. */
    private record Document(InspectionReport report, InspectionOutcome outcome) {
    }

    @Override
    public String save(String eventId, String routeId, String driverId, InspectionReport report,
                       InspectionOutcome outcome, Instant recordedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into vehicle_inspection (id, event_id, route_id, driver_id, vehicle_id, result, odometer,
                    fuel_percent, cold_box_f, report, captured_at, recorded_at)
                values (:id, :event, :route, :driver, :vehicle, :result, :odo, :fuel, :cold, cast(:report as jsonb),
                    :captured, :recorded)
                """)
                .param("id", id).param("event", eventId).param("route", routeId).param("driver", driverId)
                .param("vehicle", report.vehicleId()).param("result", outcome.result().name())
                .param("odo", report.odometerMiles()).param("fuel", report.fuelPercent())
                .param("cold", report.coldBoxF()).param("report", json.write(new Document(report, outcome)))
                .param("captured", Rows.ts(report.capturedAt())).param("recorded", Rows.ts(recordedAt))
                .update();
        jdbc.sql("""
                insert into vehicle_odometer (vehicle_id, route_id, odometer, recorded_at)
                values (:vehicle, :route, :odo, :at)
                """).param("vehicle", report.vehicleId()).param("route", routeId)
                .param("odo", report.odometerMiles()).param("at", Rows.ts(recordedAt)).update();
        return id.toString();
    }

    @Override
    public Optional<Integer> lastOdometer(String vehicleId) {
        List<Integer> max = jdbc.sql("select max(odometer) from vehicle_odometer where vehicle_id = :v")
                .param("v", vehicleId).query(Integer.class).list();
        return max.isEmpty() || max.getFirst() == null ? Optional.empty() : Optional.of(max.getFirst());
    }

    @Override
    public void recordEndOdometer(String vehicleId, String routeId, int odometer, Instant at) {
        jdbc.sql("""
                insert into vehicle_odometer (vehicle_id, route_id, odometer, recorded_at)
                values (:vehicle, :route, :odo, :at)
                """).param("vehicle", vehicleId).param("route", routeId).param("odo", odometer)
                .param("at", Rows.ts(at)).update();
    }

    @Override
    public String reportDefect(String inspectionId, String routeId, String vehicleId, String driverId,
                               boolean critical, List<String> items, String note, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into vehicle_defect (id, inspection_id, route_id, vehicle_id, driver_id, critical, items,
                    note, reported_at)
                values (:id, :inspection, :route, :vehicle, :driver, :critical, cast(:items as jsonb), :note, :at)
                """)
                .param("id", id).param("inspection", inspectionId == null ? null : UUID.fromString(inspectionId))
                .param("route", routeId).param("vehicle", vehicleId).param("driver", driverId)
                .param("critical", critical).param("items", json.write(items)).param("note", note)
                .param("at", Rows.ts(at))
                .update();
        return id.toString();
    }

    @Override
    public List<Defect> openDefects() {
        return jdbc.sql("select * from vehicle_defect where status = 'OPEN' order by critical desc, reported_at")
                .query((rs, n) -> new Defect(rs.getString("id"), rs.getString("route_id"), rs.getString("vehicle_id"),
                        rs.getString("driver_id"), rs.getBoolean("critical"), json.strings(rs.getString("items")),
                        rs.getString("note"), Rows.instant(rs, "reported_at")))
                .list();
    }

    @Override
    public boolean resolveDefect(String defectId, String by, String note, Instant at) {
        UUID id;
        try {
            id = UUID.fromString(defectId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return jdbc.sql("""
                update vehicle_defect set status = 'RESOLVED', resolved_by = :by, resolved_note = :note,
                    resolved_at = :at
                where id = :id and status = 'OPEN'
                """).param("by", by).param("note", note).param("at", Rows.ts(at)).param("id", id).update() == 1;
    }
}
