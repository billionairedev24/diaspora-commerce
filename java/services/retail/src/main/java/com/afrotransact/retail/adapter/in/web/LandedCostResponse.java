package com.afrotransact.retail.adapter.in.web;

import java.util.List;

import com.afrotransact.retail.domain.LandedCost;
import com.afrotransact.retail.domain.LandedCostLine;
import io.swagger.v3.oas.annotations.media.Schema;

/** Landed-cost breakdown (base currency minor units). */
@Schema(name = "LandedCost")
record LandedCostResponse(String currency, long fobMinor, long dutyMinor, long chargesMinor, long totalMinor,
                          List<LandedCostLine> lines) {

    static LandedCostResponse of(LandedCost lc) {
        return lc == null ? null : new LandedCostResponse(lc.currency(), lc.fobMinor(), lc.dutyMinor(),
                lc.chargesMinor(), lc.totalMinor(), lc.lines());
    }
}
