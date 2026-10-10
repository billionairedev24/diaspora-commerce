package com.afrotransact.retail.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.domain.InvoiceLine;

/**
 * A supplier invoice received by Finance. {@code fxRate} is the rate at the invoice date (supplier-currency units per
 * base unit); without it the configured rate of the day is used.
 */
public record RegisterInvoiceCommand(UUID poId, String invoiceNumber, LocalDate invoiceDate, String currency,
                                     BigDecimal fxRate, List<InvoiceLine> lines) {
}
