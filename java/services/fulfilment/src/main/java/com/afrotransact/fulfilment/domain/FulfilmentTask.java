package com.afrotransact.fulfilment.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pick and pack of one AfroTransact-fulfilled shipment in a fulfilment centre. */
public final class FulfilmentTask {

    private final String id;
    private final String shipmentId;
    private final String orderId;
    private final String orderNumber;
    private final String fulfilmentType;
    private final String deliveryMethod;
    private String nodeId;
    private TaskStatus status;
    private List<TaskLine> lines;
    private String packageLabel;
    private Double weightLb;
    private String carrier;
    private String trackingNumber;
    private String exceptionReason;
    private String operator;
    private Instant updatedAt;
    private final int version;

    public FulfilmentTask(String id, String shipmentId, String orderId, String orderNumber, String fulfilmentType,
                          String deliveryMethod, String nodeId, TaskStatus status, List<TaskLine> lines,
                          String packageLabel, Double weightLb, String carrier, String trackingNumber,
                          String exceptionReason, String operator, Instant updatedAt, int version) {
        this.id = id;
        this.shipmentId = shipmentId;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.fulfilmentType = fulfilmentType;
        this.deliveryMethod = deliveryMethod;
        this.nodeId = nodeId;
        this.status = status;
        this.lines = List.copyOf(lines);
        this.packageLabel = packageLabel;
        this.weightLb = weightLb;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.exceptionReason = exceptionReason;
        this.operator = operator;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public String id() {
        return id;
    }

    public String shipmentId() {
        return shipmentId;
    }

    public String orderId() {
        return orderId;
    }

    public String orderNumber() {
        return orderNumber;
    }

    public String fulfilmentType() {
        return fulfilmentType;
    }

    public String deliveryMethod() {
        return deliveryMethod;
    }

    public String nodeId() {
        return nodeId;
    }

    public TaskStatus status() {
        return status;
    }

    public List<TaskLine> lines() {
        return lines;
    }

    public String packageLabel() {
        return packageLabel;
    }

    public Double weightLb() {
        return weightLb;
    }

    public String carrier() {
        return carrier;
    }

    public String trackingNumber() {
        return trackingNumber;
    }

    public String exceptionReason() {
        return exceptionReason;
    }

    public String operator() {
        return operator;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public int version() {
        return version;
    }

    public boolean carrierDelivery() {
        return "CARRIER".equals(deliveryMethod);
    }

    public void assignNode(String node, Instant at) {
        if (status != TaskStatus.PICK_PENDING) {
            throw new FulfilmentConflict("fulfilment.task-started", "The node can only change before picking");
        }
        this.nodeId = node;
        this.updatedAt = at;
    }

    public void startPick(String who, Instant at) {
        if (status == TaskStatus.PICKING && who.equals(operator)) {
            return;
        }
        if (status != TaskStatus.PICK_PENDING && status != TaskStatus.PICK_EXCEPTION) {
            throw new FulfilmentConflict("fulfilment.task-invalid-transition", "Task is " + status);
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.node-required", "Assign the fulfilment centre first");
        }
        this.status = TaskStatus.PICKING;
        this.operator = who;
        this.exceptionReason = null;
        this.updatedAt = at;
    }

    /**
     * Records picked units per variant ATIN. All units picked ⇒ PICKED; fewer with a reason ⇒ PICK_EXCEPTION.
     *
     * @return true when the task is fully picked
     */
    public boolean completePick(Map<String, Integer> pickedByAtin, Map<String, String> lotsByAtin,
                                String shortReason, Instant at) {
        if (status != TaskStatus.PICKING) {
            throw new FulfilmentConflict("fulfilment.task-invalid-transition", "Start picking first (task is "
                    + status + ")");
        }
        List<TaskLine> next = new ArrayList<>();
        boolean complete = true;
        for (TaskLine l : lines) {
            int picked = pickedByAtin.getOrDefault(l.variantAtin(), 0);
            if (picked < 0 || picked > l.quantity()) {
                throw new FulfilmentRuleViolation("fulfilment.pick-quantity-invalid", "Picked " + picked + " of "
                        + l.quantity() + " for " + l.variantAtin());
            }
            complete &= picked == l.quantity();
            next.add(new TaskLine(l.lineId(), l.variantAtin(), l.title(), l.quantity(), picked,
                    lotsByAtin.getOrDefault(l.variantAtin(), l.lotIds())));
        }
        for (String atin : pickedByAtin.keySet()) {
            if (lines.stream().noneMatch(l -> l.variantAtin().equals(atin))) {
                throw new FulfilmentRuleViolation("fulfilment.pick-unknown-item", atin + " is not on this task");
            }
        }
        if (!complete && (shortReason == null || shortReason.isBlank())) {
            throw new FulfilmentRuleViolation("fulfilment.short-pick-reason-required",
                    "Some units were not picked; give a reason");
        }
        this.lines = List.copyOf(next);
        this.status = complete ? TaskStatus.PICKED : TaskStatus.PICK_EXCEPTION;
        this.exceptionReason = complete ? null : shortReason.trim();
        this.updatedAt = at;
        return complete;
    }

    public void pack(String label, double weight, String carrierName, String tracking, String who, Instant at) {
        if (status != TaskStatus.PICKED) {
            throw new FulfilmentConflict("fulfilment.task-invalid-transition", "Pick every unit first (task is "
                    + status + ")");
        }
        if (weight <= 0 || weight > 150) {
            throw new FulfilmentRuleViolation("fulfilment.weight-invalid", "Parcel weight must be 0–150 lb");
        }
        if (carrierDelivery() && (carrierName == null || carrierName.isBlank() || tracking == null
                || tracking.isBlank())) {
            throw new FulfilmentRuleViolation("fulfilment.carrier-label-required",
                    "Carrier shipments need the carrier label (carrier and tracking number) from carrier-gateway");
        }
        this.packageLabel = label;
        this.weightLb = weight;
        this.carrier = carrierName;
        this.trackingNumber = tracking;
        this.operator = who;
        this.status = TaskStatus.PACKED;
        this.updatedAt = at;
    }

    public void ready(Instant at) {
        if (status != TaskStatus.PACKED) {
            throw new FulfilmentConflict("fulfilment.task-invalid-transition", "Pack first (task is " + status + ")");
        }
        this.status = TaskStatus.READY;
        this.updatedAt = at;
    }

    /** @return false when it was already cancelled */
    public boolean cancel(String reason, Instant at) {
        if (status == TaskStatus.CANCELLED) {
            return false;
        }
        this.status = TaskStatus.CANCELLED;
        this.exceptionReason = reason;
        this.updatedAt = at;
        return true;
    }
}
