package com.afrotransact.retail.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Raise a PO. Currency is the supplier's; {@code fxRate} (supplier-currency units per base unit) is captured at the
 * order date and defaults to the configured rate of the day.
 */
public record CreatePoCommand(UUID supplierId, String destinationNodeId, String incoterm, LocalDate orderDate,
                              LocalDate expectedDate, BigDecimal fxRate, List<PoLineInput> lines) {
}
