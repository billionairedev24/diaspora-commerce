package com.afrotransact.taxreturns.application;

import java.util.List;

import com.afrotransact.taxreturns.domain.returns.InspectionOutcome;
import com.afrotransact.taxreturns.domain.returns.ItemCondition;

/** Inspection result for every line of a received RMA. */
public record InspectCommand(List<Line> lines, String note) {

    public InspectCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** {@code condition} defaults to RESTOCK for passed lines and DAMAGED for failed ones. */
    public record Line(String lineId, InspectionOutcome outcome, ItemCondition condition, String note) {
    }
}
