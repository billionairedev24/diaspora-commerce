package com.afrotransact.inventory.application;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.inventory.domain.LotCost;

/**
 * Fixes the cost of the lots of a receipt in US dollars at receiving time: validates the currency and the rate, takes
 * the receiver's rate or else the staff-managed table's, and values every line.
 */
interface LotCostValuer {

    /**
     * One cost per line, in line order. A bad line (unknown currency, non-positive rate, a currency without any rate,
     * components that do not add up) is reported by field with every other bad line, as a 422.
     *
     * @param today the receiving date at the node
     */
    List<LotCost> value(List<ReceiveCommand.Line> lines, LocalDate today);
}
