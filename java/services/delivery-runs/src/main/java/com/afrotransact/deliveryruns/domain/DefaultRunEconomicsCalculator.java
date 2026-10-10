package com.afrotransact.deliveryruns.domain;

/**
 * {@code cost(n) = (fixedMinutes / 60 × perHour + miles × perMile) + n × (perOrderMinutes / 60 × perHour)} where
 * fixed minutes are the drive (both ways when {@link EconomicsSettings#roundTrip()}) plus the seller sweep, plus one
 * pickup-point stop for {@link DropMode#PICKUP_POINT}; per-order minutes are a doorstep stop or a handover.
 */
public class DefaultRunEconomicsCalculator implements RunEconomicsCalculator {

    private static final int MAX_BREAK_EVEN = 100_000;

    private final EconomicsSettings settings;

    public DefaultRunEconomicsCalculator(EconomicsSettings settings) {
        this.settings = settings;
    }

    @Override
    public CostModel costModel(RouteEstimate route, DropMode dropMode) {
        double legs = settings.roundTrip() ? 2 : 1;
        double miles = route.roadMiles() * legs;
        double fixedMinutes = route.driveMinutes() * legs + settings.collectionMinutes();
        double perOrderMinutes;
        if (dropMode == DropMode.PICKUP_POINT) {
            fixedMinutes += settings.pickupPointStopMinutes();
            perOrderMinutes = settings.handoverMinutesPerOrder();
        } else {
            perOrderMinutes = settings.perStopMinutes();
        }
        double perMinute = settings.driverCostPerHourMinor() / 60.0;
        double fixedCost = fixedMinutes * perMinute + miles * settings.driverCostPerMileMinor();
        return new CostModel(round2(miles), round2(fixedMinutes), round2(perOrderMinutes), fixedCost,
                perOrderMinutes * perMinute);
    }

    @Override
    public Integer breakEvenOrders(long feeMinor, CostModel cost) {
        if (feeMinor <= cost.perOrderCostMinor()) {
            return null;
        }
        int n = (int) Math.max(1, Math.ceil(cost.fixedCostMinor() / (feeMinor - cost.perOrderCostMinor())));
        while (n > 1 && (long) (n - 1) * feeMinor >= cost.costFor(n - 1)) {
            n--;
        }
        while ((long) n * feeMinor < cost.costFor(n) && n < MAX_BREAK_EVEN) {
            n++;
        }
        return n >= MAX_BREAK_EVEN ? null : n;
    }

    @Override
    public EconomicsSnapshot at(int orders, long feeMinor, Long commissionShareMinor, CostModel cost) {
        long fees = orders * feeMinor;
        long revenue = fees + (commissionShareMinor == null ? 0 : commissionShareMinor);
        long estimated = cost.costFor(orders);
        return new EconomicsSnapshot(orders, fees, commissionShareMinor, revenue, estimated, revenue - estimated);
    }

    @Override
    public RunEconomics evaluate(RouteEstimate route, DropMode dropMode, long feeMinor, String currency, int minOrders,
                                 Integer capacityOrders, int orders, Long commissionShareMinor) {
        CostModel cost = costModel(route, dropMode);
        return new RunEconomics(route, cost, feeMinor, currency, breakEvenOrders(feeMinor, cost),
                at(minOrders, feeMinor, null, cost),
                capacityOrders == null ? null : at(capacityOrders, feeMinor, null, cost),
                at(orders, feeMinor, commissionShareMinor, cost));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
