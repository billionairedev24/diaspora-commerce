package com.afrotransact.retail.domain;

import java.util.List;

/** Computes landed cost per line and per unit from FOB values and landed charges. */
public interface LandedCostCalculator {

    LandedCost calculate(String baseCurrency, List<LandedCostInput> lines, List<LandedCharge> charges);
}
