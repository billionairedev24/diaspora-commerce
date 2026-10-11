package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A cycle count of the lots at one node (optionally one SKU and / or a bin prefix). The expected quantities are a
 * snapshot taken when the count starts; posting adjusts each lot by the variance (counted − expected) with a reason
 * code, so units picked or received while the count was running are not lost.
 */
public record CycleCount(UUID id, String number, String nodeId, String sku, String binPrefix, String ownerOrg,
                         boolean blind, CycleCountStatus status, String note, List<CycleCountLine> lines,
                         String createdBy, Instant createdAt, Instant countedAt, String postedBy, Instant postedAt,
                         Instant updatedAt, long version) {

    public CycleCount {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Units counted over (positive) or short (negative) across every counted line. */
    public int netVariance() {
        return lines.stream().mapToInt(CycleCountLine::variance).sum();
    }

    public long varianceLines() {
        return lines.stream().filter(l -> l.counted() && l.variance() != 0).count();
    }

    public long countedLines() {
        return lines.stream().filter(CycleCountLine::counted).count();
    }

    /** Records counts by line number; every line counted moves the count to COUNTED. */
    public CycleCount count(Map<Integer, Entry> entries, String actor, Instant now) {
        requireStatus(CycleCountStatus.OPEN, CycleCountStatus.COUNTED);
        Map<String, String> errors = new LinkedHashMap<>();
        Map<Integer, CycleCountLine> byNo = new LinkedHashMap<>();
        lines.forEach(l -> byNo.put(l.lineNo(), l));
        entries.forEach((no, e) -> {
            if (!byNo.containsKey(no)) {
                errors.put("lines[" + no + "]", "No such line");
            } else if (e.qty() < 0) {
                errors.put("lines[" + no + "].countedQty", "Must be ≥ 0");
            }
        });
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Invalid counts", errors);
        }
        List<CycleCountLine> next = new ArrayList<>();
        for (CycleCountLine l : lines) {
            Entry e = entries.get(l.lineNo());
            next.add(e == null ? l : l.count(e.qty(), e.reason(), e.note(), actor, now));
        }
        boolean all = next.stream().allMatch(CycleCountLine::counted);
        return new CycleCount(id, number, nodeId, sku, binPrefix, ownerOrg, blind,
                all ? CycleCountStatus.COUNTED : CycleCountStatus.OPEN, note, next, createdBy, createdAt,
                all ? now : null, postedBy, postedAt, now, version);
    }

    /** Checks that the count can be posted: every line counted, every variance with a reason. */
    public void requirePostable() {
        requireStatus(CycleCountStatus.COUNTED, CycleCountStatus.OPEN);
        Map<String, String> errors = new LinkedHashMap<>();
        for (CycleCountLine l : lines) {
            if (!l.counted()) {
                errors.put("lines[" + l.lineNo() + "].countedQty", "Count lot " + l.lotNo() + " first");
            } else if (l.variance() != 0 && l.reasonCode() == null) {
                errors.put("lines[" + l.lineNo() + "].reasonCode", "Give a reason for the variance of "
                        + l.variance());
            }
        }
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.cycle-count-incomplete",
                    "Count every line and give a reason for each variance before posting", errors);
        }
    }

    public CycleCount posted(List<CycleCountLine> postedLines, String actor, Instant now) {
        return new CycleCount(id, number, nodeId, sku, binPrefix, ownerOrg, blind, CycleCountStatus.POSTED, note,
                postedLines, createdBy, createdAt, countedAt == null ? now : countedAt, actor, now, now, version);
    }

    public CycleCount cancelled(Instant now) {
        requireStatus(CycleCountStatus.OPEN, CycleCountStatus.COUNTED);
        return new CycleCount(id, number, nodeId, sku, binPrefix, ownerOrg, blind, CycleCountStatus.CANCELLED, note,
                lines, createdBy, createdAt, countedAt, postedBy, postedAt, now, version);
    }

    private void requireStatus(CycleCountStatus... allowed) {
        for (CycleCountStatus s : allowed) {
            if (status == s) {
                return;
            }
        }
        throw new InventoryStateConflict("inventory.cycle-count-invalid-state", "Cycle count " + number + " is "
                + status);
    }

    /** A counted quantity with its variance reason and note. */
    public record Entry(int qty, CycleCountReason reason, String note) {
    }
}
