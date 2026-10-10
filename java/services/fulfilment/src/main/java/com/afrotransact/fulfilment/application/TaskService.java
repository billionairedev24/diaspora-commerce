package com.afrotransact.fulfilment.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.fulfilment.domain.FulfilmentTask;
import com.afrotransact.fulfilment.domain.TaskStatus;

/**
 * Pick and pack of FBA / Retail 1P shipments (fulfilment:operate). Starting a pick confirms the shipment in
 * orders-service, packing moves it to packed and staging to ready.
 */
public interface TaskService {

    List<FulfilmentTask> list(String nodeId, TaskStatus status, int limit);

    FulfilmentTask get(String id);

    FulfilmentTask assignNode(String id, String nodeId);

    FulfilmentTask startPick(String id);

    /**
     * @param picked      units picked per variant ATIN
     * @param lots        lot ids picked from per variant ATIN (optional)
     * @param shortReason required when fewer units than ordered were picked
     */
    FulfilmentTask completePick(String id, Map<String, Integer> picked, Map<String, String> lots, String shortReason);

    /**
     * Packs the parcel. AfroTransact Delivery shipments keep the label issued at intake (ATS…); carrier shipments need
     * the carrier label bought from carrier-gateway, which is attached in orders-service for tracking.
     */
    FulfilmentTask pack(String id, double weightLb, String carrier, String trackingNumber);

    /** Staged at the outbound dock: ready for the carrier or a driver route. */
    FulfilmentTask ready(String id);
}
