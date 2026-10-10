package com.afrotransact.retail.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.application.SupplierCommand;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierContact;
import com.afrotransact.retail.domain.SupplierStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies for suppliers. */
final class SupplierDtos {

    private SupplierDtos() {
    }

    @Schema(name = "SupplierContact")
    record ContactDto(@NotBlank @Size(max = 120) String name, @Size(max = 60) String role,
                      @Size(max = 200) String email, @Size(max = 40) String phone) {

        SupplierContact toDomain() {
            return new SupplierContact(name, role, email, phone);
        }

        static ContactDto of(SupplierContact c) {
            return new ContactDto(c.name(), c.role(), c.email(), c.phone());
        }
    }

    @Schema(name = "SupplierRequest", description = "Supplier master data; code is fixed after creation")
    record SupplierRequest(@Schema(example = "SUP-ACC") @Pattern(regexp = "SUP-[A-Z0-9]{2,12}") String code,
                           @NotBlank @Size(max = 200) String name,
                           @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String country,
                           @Size(max = 200) String city,
                           @NotBlank @Size(min = 3, max = 3) String currency,
                           @Schema(example = "Net 30 · 30% deposit") @Size(max = 200) String paymentTerms,
                           @PositiveOrZero @Max(365) int leadTimeDays,
                           @Size(max = 30) List<@NotBlank @Size(max = 120) String> certifications,
                           @Size(max = 20) List<@Valid ContactDto> contacts) {

        SupplierCommand toCommand() {
            return new SupplierCommand(code, name, country, city, currency, paymentTerms, leadTimeDays,
                    certifications, contacts == null ? List.of() : contacts.stream().map(ContactDto::toDomain)
                    .toList());
        }
    }

    @Schema(name = "SupplierStatusRequest")
    record StatusRequest(@NotNull SupplierStatus status, @Size(max = 1000) String reason) {
    }

    @Schema(name = "SupplierScorecard", description = "Updated by every completed goods receipt")
    record ScorecardDto(int receipts, int onTimeReceipts, long unitsReceived, long unitsDefective,
                        BigDecimal onTimePercent, BigDecimal defectPercent) {
    }

    @Schema(name = "Supplier")
    record SupplierResponse(UUID id, String code, String name, String country, String city, String currency,
                            String paymentTerms, int leadTimeDays, List<String> certifications,
                            List<ContactDto> contacts, String status, String statusReason, ScorecardDto scorecard,
                            boolean canReceivePurchaseOrders, Instant createdAt, Instant updatedAt, long version) {

        static SupplierResponse of(Supplier s) {
            return new SupplierResponse(s.id(), s.code(), s.name(), s.country(), s.city(), s.currency(),
                    s.paymentTerms(), s.leadTimeDays(), s.certifications(),
                    s.contacts().stream().map(ContactDto::of).toList(), s.status().name(), s.statusReason(),
                    new ScorecardDto(s.scorecard().receipts(), s.scorecard().onTimeReceipts(),
                            s.scorecard().unitsReceived(), s.scorecard().unitsDefective(),
                            s.scorecard().onTimePercent(), s.scorecard().defectPercent()),
                    s.status().canReceivePurchaseOrders(), s.createdAt(), s.updatedAt(), s.version());
        }
    }
}
