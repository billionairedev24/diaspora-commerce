package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Landed cost = FOB + duty + freight, insurance, brokerage, drayage, fees and receiving.
 * <ul>
 *   <li>Each charge is allocated by its basis (value, weight or quantity) with the {@link CostAllocator}.</li>
 *   <li>Without an explicit DUTY charge, duty is estimated per line as FOB × HTS duty rate (half-up).</li>
 *   <li>Unit cost = line total ÷ units, half-up; lines with zero units carry no unit cost.</li>
 * </ul>
 */
public class DefaultLandedCostCalculator implements LandedCostCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CostAllocator allocator;

    public DefaultLandedCostCalculator(CostAllocator allocator) {
        this.allocator = allocator;
    }

    @Override
    public LandedCost calculate(String baseCurrency, List<LandedCostInput> lines, List<LandedCharge> charges) {
        String base = Currencies.require(baseCurrency);
        if (lines == null || lines.isEmpty()) {
            throw new RetailRuleViolation("retail.landed-cost-empty", "No lines to cost");
        }
        List<LandedCharge> all = charges == null ? List.of() : charges;
        for (LandedCharge c : all) {
            if (!c.currency().equals(base)) {
                throw new RetailRuleViolation("retail.charge-invalid", c.type() + " must be in " + base
                        + " (landed cost is kept in the base currency)");
            }
        }
        int n = lines.size();
        long[] duty = new long[n];
        List<List<ChargeShare>> shares = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            shares.add(new ArrayList<>());
        }
        boolean explicitDuty = all.stream().anyMatch(c -> c.type() == ChargeType.DUTY);
        if (!explicitDuty) {
            for (int i = 0; i < n; i++) {
                LandedCostInput l = lines.get(i);
                duty[i] = BigDecimal.valueOf(l.fobValueMinor()).multiply(l.dutyRatePercent())
                        .divide(HUNDRED, 0, RoundingMode.HALF_UP).longValueExact();
            }
        }
        for (LandedCharge charge : all) {
            List<Long> split = allocator.allocate(charge.amountMinor(), weights(lines, charge.basis()));
            for (int i = 0; i < n; i++) {
                if (charge.type() == ChargeType.DUTY) {
                    duty[i] += split.get(i);
                } else {
                    shares.get(i).add(new ChargeShare(charge.type(), charge.basis(), split.get(i)));
                }
            }
        }
        List<LandedCostLine> out = new ArrayList<>(n);
        long fob = 0;
        long dutyTotal = 0;
        long chargeTotal = 0;
        for (int i = 0; i < n; i++) {
            LandedCostInput l = lines.get(i);
            long lineCharges = shares.get(i).stream().mapToLong(ChargeShare::amountMinor).sum();
            long total = l.fobValueMinor() + duty[i] + lineCharges;
            long unit = l.qty() == 0 ? 0 : BigDecimal.valueOf(total)
                    .divide(BigDecimal.valueOf(l.qty()), 0, RoundingMode.HALF_UP).longValueExact();
            out.add(new LandedCostLine(l.lineNo(), l.sku(), l.qty(), l.fobValueMinor(), duty[i], shares.get(i),
                    total, unit));
            fob += l.fobValueMinor();
            dutyTotal += duty[i];
            chargeTotal += lineCharges;
        }
        return new LandedCost(base, out, fob, dutyTotal, chargeTotal, fob + dutyTotal + chargeTotal);
    }

    private static List<BigDecimal> weights(List<LandedCostInput> lines, AllocationBasis basis) {
        return lines.stream().map(l -> switch (basis) {
            case VALUE -> BigDecimal.valueOf(l.fobValueMinor());
            case WEIGHT -> BigDecimal.valueOf((long) l.qty() * l.unitWeightGrams());
            case QUANTITY -> BigDecimal.valueOf(l.qty());
        }).toList();
    }
}
