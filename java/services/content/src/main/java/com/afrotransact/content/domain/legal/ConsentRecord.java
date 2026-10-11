package com.afrotransact.content.domain.legal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A stored, auditable cookie-consent decision.
 *
 * @param id            record id (returned to the browser)
 * @param subject       anonymous device id or user id
 * @param categories    final per-category choice after normalisation
 * @param gpc           Global Privacy Control was present
 * @param saleOptOut    "Do not sell or share my personal information" (always true with GPC)
 * @param policyVersion Cookie Notice version the visitor saw
 * @param source        banner, preferences, gpc, app
 * @param recordedAt    when
 */
public record ConsentRecord(UUID id, String subject, Map<String, Boolean> categories, boolean gpc,
                            boolean saleOptOut, String policyVersion, String source, Instant recordedAt) {
}
