package com.afrotransact.orders.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.orders.domain.DeliveryJob;
import com.afrotransact.orders.domain.DeliveryProofRecord;

/** The Delivery desk's own tables (job, proof, audit) and the queue read over shipments. */
public interface DeliveryDeskRepository {

    /** Queue filters; null or empty means "any". {@code assignee}: a user id, or the literal "unassigned". */
    record Filter(List<String> statuses, String mode, String text, String assignee, String city, String seller,
                  String sort, boolean descending) {
    }

    /** One queue row: shipment, order, seller, customer (name and city) and the job. */
    record Row(String shipmentId, String orderNumber, int seq, int shipmentCount, String sellerName,
               String fulfilmentType, boolean fromSellerStore, String nodeId, String pickupLocationId, String status,
               LocalDate promisedBy, Instant statusChangedAt, Instant deliveredAt, boolean perishable,
               boolean handoverRequired, int units, String customerName, String customerCity,
               String customerState, String customerPostalCode, String assigneeId, String assigneeName, int attempts,
               long jobVersion, boolean proofAttached) {
    }

    /** One audit line. */
    record AuditLine(long id, String shipmentId, String action, String fromStatus, String toStatus, String actorId,
                     String actorName, Map<String, Object> detail, Instant occurredAt) {
    }

    Page<Row> queue(Filter filter, int offset, int limit);

    /** Counts of the in-flight statuses (the tab badges), over the whole queue. */
    Map<String, Long> counts();

    Optional<DeliveryJob> findJob(String shipmentId);

    /** {@code SELECT … FOR UPDATE} on the job row (callers hold the order lock first). */
    Optional<DeliveryJob> lockJob(String shipmentId);

    /** Inserts a new job (version 0 stored as given) or updates it when {@code expectedVersion} still matches. */
    boolean saveJob(DeliveryJob job, long expectedVersion);

    Optional<DeliveryProofRecord> findProof(String shipmentId);

    void insertProof(DeliveryProofRecord proof);

    /** Writes a correction; false when the stored version moved. */
    boolean updateProof(DeliveryProofRecord proof, long expectedVersion);

    /** Appends an audit line; false when the same idempotency key was already recorded for the shipment. */
    boolean appendAudit(String orderId, AuditLine line, String idempotencyKey);

    List<AuditLine> audit(String shipmentId);

    /** Shipment ids this key was already applied to (idempotent replays). */
    boolean auditKeySeen(String shipmentId, String idempotencyKey);
}
