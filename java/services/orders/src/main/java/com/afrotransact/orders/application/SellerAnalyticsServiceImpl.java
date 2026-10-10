package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.port.SellerAnalyticsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellerAnalyticsServiceImpl implements SellerAnalyticsService {

    private static final int TOP_PRODUCTS = 5;

    private final OrdersAccess access;
    private final SellerAnalyticsRepository analytics;
    private final OrdersSettings settings;
    private final Clock clock;

    SellerAnalyticsServiceImpl(OrdersAccess access, SellerAnalyticsRepository analytics, OrdersSettings settings,
                               Clock clock) {
        this.access = access;
        this.analytics = analytics;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public SellerAnalytics analytics(String sellerOrgId, LocalDate from, LocalDate to) {
        String org = access.sellerOrg(sellerOrgId);
        ZoneId zone = ZoneId.of(settings.zone());
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(zone));
        LocalDate start = from != null ? from : end.minusDays(29);
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new OrdersRuleViolation("orders.invalid-range", "Choose a range of 1 to " + MAX_DAYS + " days");
        }
        Instant fromAt = start.atStartOfDay(zone).toInstant();
        Instant toAt = end.plusDays(1).atStartOfDay(zone).toInstant();

        Map<LocalDate, SellerAnalyticsRepository.DayRow> byDay = new LinkedHashMap<>();
        analytics.days(org, fromAt, toAt, zone.getId()).forEach(d -> byDay.put(d.date(), d));
        List<SellerAnalytics.Day> days = new ArrayList<>();
        long sales = 0;
        int units = 0;
        int orders = 0;
        int checkouts = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            SellerAnalyticsRepository.DayRow r = byDay.getOrDefault(d,
                    new SellerAnalyticsRepository.DayRow(d, 0, 0, 0, 0));
            days.add(new SellerAnalytics.Day(d, r.salesMinor(), r.units(), r.orders(), r.checkouts(),
                    rate(r.orders(), r.checkouts())));
            sales += r.salesMinor();
            units += r.units();
            orders += r.orders();
            checkouts += r.checkouts();
        }
        List<SellerAnalytics.Product> top = analytics.topProducts(org, fromAt, toAt, TOP_PRODUCTS).stream()
                .map(p -> new SellerAnalytics.Product(p.productAtin(), p.title(), p.revenueMinor(), p.units()))
                .toList();
        Map<String, Integer> fulfilment = new LinkedHashMap<>();
        analytics.byFulfilment(org, fromAt, toAt).forEach(f -> fulfilment.merge(
                "SELLER_FULFILLED".equals(f.fulfilmentType()) ? "SELLER" : "AFROTRANSACT", f.orders(), Integer::sum));
        return new SellerAnalytics(org, analytics.currency(org), start, end, days, top,
                fulfilment.entrySet().stream().map(e -> new SellerAnalytics.FulfilmentCount(e.getKey(), e.getValue()))
                        .toList(), analytics.toShip(org),
                new SellerAnalytics.Totals(sales, units, orders, checkouts, rate(orders, checkouts)));
    }

    private static double rate(int orders, int checkouts) {
        return checkouts == 0 ? 0 : Math.round(10_000.0 * orders / checkouts) / 10_000.0;
    }
}
