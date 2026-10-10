package com.afrotransact.retail.domain;

import java.util.List;

/** Validation of one receipt line: blocking errors, warnings and where its good units go. */
public record LineCheck(int lineNo, List<String> errors, List<String> warnings, Disposition disposition,
                        Integer remainingDays) {

    public LineCheck {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }
}
