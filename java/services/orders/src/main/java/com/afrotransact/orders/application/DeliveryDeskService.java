package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;


/**
 * The staff console Delivery module (docs/engineering/delivery-fulfilment.md): AfroTransact runs AfroTransact-delivered
 * shipments end to end without the driver app. Reads need {@code delivery:read} (or manage), every action needs
 * {@code delivery:manage}, checked here on the server. Every action is audited, idempotent per Idempotency-Key and
 * optimistic-locked on the job version.
 */
public interface DeliveryDeskService {

    /** Queue filters (statuses are lower-case wire names; null = the active statuses). */
    record Query(List<String> statuses, String mode, String text, String assignee, String city, String seller,
                 String sort, boolean descending) {
    }

    /** What a caller sends with an action: the job version it saw, its idempotency key and a note. */
    record Command(Long expectedVersion, String idempotencyKey, String note) {
    }

    /** The proof staff attach when marking delivered, or correct later. */
    record ProofInput(List<String> photoAssetIds, String recipientName, String note, Double latitude,
                      Double longitude) {
    }

    /** One queue row. Names and cities, never ids (the BFF resolves the origin city). */
    record Row(String shipmentId, String orderNumber, String shipmentLabel, String mode, String status,
               String sellerName, String nodeId, String pickupLocationId, String customerName, String customerCity,
               String customerState, String customerPostalCode, int units, boolean perishable, LocalDate promisedBy,
               String assigneeName, String assigneeId, int attempts, long version, boolean proofAttached,
               Instant updatedAt, Instant deliveredAt) {
    }

    /** One page of the queue and the count per status over the whole queue. */
    record DeskPage(List<Row> items, int page, int size, long total, Map<String, Long> counts) {
    }

    /** A person who can be assigned a delivery. */
    record Courier(String id, String name) {
    }

    /** One item to deliver. */
    record Item(String title, int quantity) {
    }

    /** Where it goes. The street address and phone are shown only to people who can manage deliveries. */
    record Destination(String name, String line1, String line2, String city, String state, String postalCode,
                       String phone, String instructions, boolean handToCustomer) {
    }

    /** The job: who has it, how many attempts failed and the version to send back with the next action. */
    record Job(String assigneeId, String assigneeName, Instant assignedAt, String assignedByName, int attempts,
               String lastFailure, long version) {
    }

    /** The saved proof, as staff see it. */
    record Proof(List<String> photoAssetIds, String recipientName, String note, Double latitude, Double longitude,
                 Instant deliveredAt, String recordedByName, Instant correctedAt, String correctedByName,
                 int corrections, long version) {
    }

    /** One audit line. */
    record AuditEntry(String action, String fromStatus, String toStatus, String actorName, Map<String, Object> detail,
                      Instant at) {
    }

    /** One delivery in full. */
    record Detail(Row row, Destination destination, List<Item> items, Job job, Proof proof, List<AuditEntry> audit,
                  List<String> nextStatuses, boolean canManage) {
    }

    DeskPage list(Query query, int page, int size);

    Detail get(String shipmentId);

    /** Staff who can be assigned (enabled staff holding delivery:manage). */
    List<Courier> couriers();

    /** The caller takes the job. */
    Detail take(String shipmentId, Command command);

    /** The job goes to a named staff member or courier holding delivery:manage. */
    Detail assign(String shipmentId, String assigneeId, Command command);

    Detail pickedUp(String shipmentId, Command command);

    Detail outForDelivery(String shipmentId, Command command);

    /** Delivered: needs the proof (at least one photo). Saved once. */
    Detail delivered(String shipmentId, ProofInput proof, Command command);

    /** A failed attempt with a reason; it can be attempted again with {@link #outForDelivery}. */
    Detail failed(String shipmentId, String reason, Command command);

    /** An admin correction of a saved proof; needs a reason; the old values go to the audit trail. */
    Detail correctProof(String shipmentId, ProofInput proof, String reason, Command command);
}
