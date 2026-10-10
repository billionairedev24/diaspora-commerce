package com.afrotransact.retail.adapter.in.web;

import com.afrotransact.retail.domain.AllocationBasis;
import com.afrotransact.retail.domain.ChargeType;
import com.afrotransact.retail.domain.LandedCharge;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** A landed-cost charge in base currency; basis defaults per type (duty/insurance by value, freight by weight …). */
@Schema(name = "LandedCharge")
record ChargeDto(@NotNull ChargeType type, @PositiveOrZero long amountMinor,
                 @NotBlank @Size(min = 3, max = 3) String currency, AllocationBasis basis,
                 @Size(max = 120) String reference) {

    LandedCharge toDomain() {
        return new LandedCharge(type, amountMinor, currency, basis, reference);
    }

    static ChargeDto of(LandedCharge c) {
        return new ChargeDto(c.type(), c.amountMinor(), c.currency(), c.basis(), c.reference());
    }
}
