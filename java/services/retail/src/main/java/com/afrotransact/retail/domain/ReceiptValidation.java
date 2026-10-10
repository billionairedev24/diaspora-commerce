package com.afrotransact.retail.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of validating a receipt; {@code ok} means it can be completed. */
public record ReceiptValidation(List<LineCheck> lines, List<LineVariance> variances, List<String> receiptErrors,
                                boolean ok) {

    public ReceiptValidation {
        lines = List.copyOf(lines);
        variances = List.copyOf(variances);
        receiptErrors = List.copyOf(receiptErrors);
    }

    public LineCheck line(int lineNo) {
        return lines.stream().filter(l -> l.lineNo() == lineNo).findFirst().orElseThrow();
    }

    /** Errors keyed {@code lines[<lineNo>]} / {@code poLine[<n>]} / {@code receipt} for RFC 9457 field errors. */
    public Map<String, String> fieldErrors() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!receiptErrors.isEmpty()) {
            out.put("receipt", String.join("; ", receiptErrors));
        }
        lines.stream().filter(l -> !l.errors().isEmpty())
                .forEach(l -> out.put("lines[" + l.lineNo() + "]", String.join("; ", l.errors())));
        variances.stream().filter(LineVariance::overBlocked)
                .forEach(v -> out.put("poLine[" + v.poLineNo() + "]", "Received " + v.received() + " of "
                        + v.expected() + " expected: more than the over-receipt tolerance; purchasing must approve"));
        return out;
    }
}
