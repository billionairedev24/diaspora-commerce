package com.afrotransact.retail.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.application.RegisterInvoiceCommand;
import com.afrotransact.retail.domain.InvoiceLine;
import com.afrotransact.retail.domain.MatchResult;
import com.afrotransact.retail.domain.SupplierInvoice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies for supplier invoices and the three-way match. */
final class InvoiceDtos {

    private InvoiceDtos() {
    }

    @Schema(name = "InvoiceLineRequest", description = "Quantity and unit price (invoice currency minor units)")
    record InvoiceLineRequest(@Positive int poLineNo, @Positive int qty, @PositiveOrZero long unitPriceMinor) {

        InvoiceLine toDomain() {
            return new InvoiceLine(poLineNo, qty, unitPriceMinor);
        }
    }

    @Schema(name = "RegisterInvoiceRequest")
    record RegisterInvoiceRequest(@NotNull UUID poId, @NotBlank @Size(max = 60) String invoiceNumber,
                                  @NotNull LocalDate invoiceDate, @Size(min = 3, max = 3) String currency,
                                  @Schema(description = "Rate at the invoice date (currency units per base unit)")
                                  @DecimalMin(value = "0", inclusive = false) BigDecimal fxRate,
                                  @NotEmpty @Size(max = 200) List<@Valid InvoiceLineRequest> lines) {

        RegisterInvoiceCommand toCommand() {
            return new RegisterInvoiceCommand(poId, invoiceNumber, invoiceDate, currency, fxRate,
                    lines.stream().map(InvoiceLineRequest::toDomain).toList());
        }
    }

    @Schema(name = "SupplierInvoice", description = "status MATCHED | EXCEPTION (routed to the buyer) | ON_HOLD | "
            + "APPROVED | REJECTED | PAYMENT_RELEASED")
    record InvoiceResponse(UUID id, UUID supplierId, UUID poId, String poNumber, String invoiceNumber,
                           LocalDate invoiceDate, String currency, BigDecimal fxRate, long totalMinor,
                           List<InvoiceLine> lines, String status, MatchResult match, String resolutionNote,
                           String resolvedBy, UUID journalEntryId, Instant createdAt, Instant updatedAt, long version) {

        static InvoiceResponse of(SupplierInvoice i) {
            return new InvoiceResponse(i.id(), i.supplierId(), i.poId(), i.poNumber(), i.invoiceNumber(),
                    i.invoiceDate(), i.currency(), i.fx().unitsPerBase(), i.totalMinor(), i.lines(),
                    i.status().name(), i.match(), i.resolutionNote(), i.resolvedBy(), i.journalEntryId(),
                    i.createdAt(), i.updatedAt(), i.version());
        }
    }
}
