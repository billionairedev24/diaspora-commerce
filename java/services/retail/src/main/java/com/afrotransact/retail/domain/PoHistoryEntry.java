package com.afrotransact.retail.domain;

import java.time.Instant;

/** Audit trail entry shown on the PO screen ("Approved · Kwesi D. (Finance)"). */
public record PoHistoryEntry(Instant at, String status, String by, String note) {
}
