package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.payments.domain.StatementLine;

/** The seller statement: movements of an organisation's payable, label charges as their own lines (S256). */
public interface SellerStatementService {

    /**
     * Lines posted in [from, to) (default: the last 90 days), newest first, at most {@code limit} (1–500). Org
     * members see their own organisation; staff name it.
     */
    List<StatementLine> statement(String orgId, String currency, Instant from, Instant to, int limit);
}
