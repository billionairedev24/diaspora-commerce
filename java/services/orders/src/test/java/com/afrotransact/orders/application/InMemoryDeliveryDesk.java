package com.afrotransact.orders.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.orders.domain.DeliveryJob;
import com.afrotransact.orders.domain.DeliveryProofRecord;
import com.afrotransact.orders.port.DeliveryDeskRepository;
import com.afrotransact.orders.port.Page;

/** A faithful in-memory stand-in for the desk tables: optimistic versions and (shipment, key) audit uniqueness. */
final class InMemoryDeliveryDesk implements DeliveryDeskRepository {

    final Map<String, DeliveryJob> jobs = new HashMap<>();
    final Map<String, DeliveryProofRecord> proofs = new HashMap<>();
    final List<AuditLine> lines = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    @Override
    public Page<Row> queue(Filter filter, int offset, int limit) {
        return new Page<>(List.of(), offset, limit, 0);
    }

    @Override
    public Map<String, Long> counts() {
        return Map.of();
    }

    @Override
    public Optional<DeliveryJob> findJob(String shipmentId) {
        return Optional.ofNullable(jobs.get(shipmentId));
    }

    @Override
    public Optional<DeliveryJob> lockJob(String shipmentId) {
        return findJob(shipmentId);
    }

    @Override
    public boolean saveJob(DeliveryJob job, long expectedVersion) {
        DeliveryJob stored = jobs.get(job.shipmentId());
        if (stored == null ? expectedVersion != 0 : stored.version() != expectedVersion) {
            return false;
        }
        jobs.put(job.shipmentId(), job);
        return true;
    }

    @Override
    public Optional<DeliveryProofRecord> findProof(String shipmentId) {
        return Optional.ofNullable(proofs.get(shipmentId));
    }

    @Override
    public void insertProof(DeliveryProofRecord proof) {
        if (proofs.putIfAbsent(proof.shipmentId(), proof) != null) {
            throw new IllegalStateException("one proof per shipment");
        }
    }

    @Override
    public boolean updateProof(DeliveryProofRecord proof, long expectedVersion) {
        DeliveryProofRecord stored = proofs.get(proof.shipmentId());
        if (stored == null || stored.version() != expectedVersion) {
            return false;
        }
        proofs.put(proof.shipmentId(), new DeliveryProofRecord(proof.id(), proof.shipmentId(), proof.photoAssetIds(),
                proof.recipientName(), proof.note(), proof.latitude(), proof.longitude(), proof.deliveredAt(),
                proof.recordedById(), proof.recordedByName(), proof.correctedAt(), proof.correctedById(),
                proof.correctedByName(), proof.corrections(), stored.version() + 1, proof.createdAt()));
        return true;
    }

    @Override
    public boolean appendAudit(String orderId, AuditLine line, String key) {
        String unique = line.shipmentId() + "|" + key;
        if (key != null && keys.contains(unique)) {
            return false;
        }
        if (key != null) {
            keys.add(unique);
        }
        lines.add(line);
        return true;
    }

    @Override
    public List<AuditLine> audit(String shipmentId) {
        return lines.stream().filter(l -> l.shipmentId().equals(shipmentId)).toList();
    }

    @Override
    public boolean auditKeySeen(String shipmentId, String idempotencyKey) {
        return idempotencyKey != null && keys.contains(shipmentId + "|" + idempotencyKey);
    }
}
