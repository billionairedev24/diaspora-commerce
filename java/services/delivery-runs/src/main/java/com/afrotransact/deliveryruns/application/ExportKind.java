package com.afrotransact.deliveryruns.application;

import com.afrotransact.deliveryruns.domain.RuleViolation;

/** CSV exports of a window ({@code GET /v1/preorders/{id}/exports/{kind}.csv}). */
public enum ExportKind {
    ORDERS("orders"),
    ORDERS_FILTERED("orders-filtered"),
    PICKING_LIST("picking-list"),
    ADVERTORIAL_REPORT("advertorial-report");

    private final String path;

    ExportKind(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static ExportKind fromPath(String path) {
        for (ExportKind k : values()) {
            if (k.path.equals(path)) {
                return k;
            }
        }
        throw new RuleViolation("delivery-runs.invalid-export",
                "Exports are orders, orders-filtered, picking-list and advertorial-report");
    }
}
