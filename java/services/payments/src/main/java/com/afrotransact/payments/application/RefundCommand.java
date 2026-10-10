package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.Money;

/** Line-level partial refund. */
public record RefundCommand(List<Line> lines, String reason) {

    public RefundCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Amounts of one line to give back. */
    public record Line(String lineId, Money items, Money shipping, Money tax) {
    }
}
