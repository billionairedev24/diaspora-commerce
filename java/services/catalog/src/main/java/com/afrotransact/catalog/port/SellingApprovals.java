package com.afrotransact.catalog.port;

import java.util.List;

/**
 * seller-service's answer to "can this organisation sell this product?" (SELL, APPLY or NOT_AVAILABLE, with the reason
 * and what is missing). The catalog enforces it when a listing goes live; it never decides it.
 */
public interface SellingApprovals {

    /** @throws SellingUnavailable when seller-service cannot be asked: callers fail closed */
    Status statusOf(String orgId, Query query);

    /**
     * @param categoryCodes the listing's category code and its ancestors (comma-separated)
     * @param material      jewelry: gold, silver, a gemstone... (null when not known)
     */
    record Query(String categoryCodes, String brand, String productTypeCode, String material, String gtin) {
    }

    /** @param level SELL, APPLY or NOT_AVAILABLE */
    record Status(String level, String reason, List<Missing> missing, List<String> pendingFacts) {

        public Status {
            missing = missing == null ? List.of() : List.copyOf(missing);
            pendingFacts = pendingFacts == null ? List.of() : List.copyOf(pendingFacts);
        }

        public boolean sell() {
            return "SELL".equals(level);
        }
    }

    /** One scope in the way: {@code state} NOT_APPLIED, APPLIED, NEEDS_ATTENTION, EXPIRED or NOT_AVAILABLE. */
    record Missing(String scopeType, String scopeKey, String label, String level, String state, String message,
                   boolean canApply) {
    }

    /** seller-service did not answer. */
    class SellingUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SellingUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
