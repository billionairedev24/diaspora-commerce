package com.afrotransact.fulfilment.domain;

import java.util.List;

/**
 * Evaluated inspection.
 *
 * @param result           overall result
 * @param failedItems      every failed item
 * @param criticalFailures failed critical items (brakes, tyres, lights)
 * @param warnings         advisory notes such as low fuel
 */
public record InspectionOutcome(InspectionResult result, List<InspectionItem> failedItems,
                                List<InspectionItem> criticalFailures, List<String> warnings) {

    public InspectionOutcome {
        failedItems = List.copyOf(failedItems);
        criticalFailures = List.copyOf(criticalFailures);
        warnings = List.copyOf(warnings);
    }
}
