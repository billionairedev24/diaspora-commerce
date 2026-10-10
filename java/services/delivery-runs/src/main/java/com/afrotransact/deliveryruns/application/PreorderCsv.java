package com.afrotransact.deliveryruns.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderSummary;
import com.afrotransact.deliveryruns.domain.PreorderWindow;

/** The design's CSV layouts (orders, picking list, advertorial report). */
final class PreorderCsv {

    static final List<String> ORDER_COLUMNS = List.of("Order", "Pre-order", "Customer", "Phone", "Email", "Street",
            "City", "Region", "Postal code", "Delivery note", "Placed", "Channel", "Tracking tag", "Items", "Units",
            "Fulfilment", "Total", "Status", "Note");
    static final List<String> PICKING_COLUMNS = List.of("Product", "Seller", "Units");
    static final List<String> REPORT_COLUMNS = List.of("Placement", "Creative", "Impressions", "Clicks", "CTR %",
            "Pre-orders");

    private PreorderCsv() {
    }

    static List<List<String>> orders(PreorderWindow w, List<PreorderOrder> orders) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(ORDER_COLUMNS);
        for (PreorderOrder o : orders) {
            String items = o.lines().stream().map(l -> l.name() + " x" + l.qty()).collect(Collectors.joining("; "));
            rows.add(List.of(o.number(), w.details().name(), nz(o.customerName()), nz(o.phone()), nz(o.email()),
                    nz(o.address().line()), nz(o.address().city()), nz(o.address().region()), nz(o.address().zip()),
                    nz(o.deliveryNote()), o.placedAt().toString(), o.channel().label(), nz(o.trackingTag()), items,
                    String.valueOf(o.units()), o.fulfilment() == PreorderFulfilment.DOOR ? "Door" : "Pickup",
                    BigDecimal.valueOf(o.totalMinor(), 2).toPlainString(), o.status().label(), nz(o.note())));
        }
        return rows;
    }

    /** Units per product, most first (from the summary's full product tally). */
    static List<List<String>> pickingList(List<PreorderSummary.ProductUnits> products) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(PICKING_COLUMNS);
        products.forEach(p -> rows.add(List.of(p.name(), nz(p.seller()), String.valueOf(p.units()))));
        return rows;
    }

    static List<List<String>> advertorialReport(PreorderSummary summary) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(REPORT_COLUMNS);
        for (PreorderSummary.PlacementRow p : summary.placements()) {
            rows.add(List.of(p.name(), p.creative() ? "yes" : "no",
                    p.impressions() == null ? "" : String.valueOf(p.impressions()),
                    p.clicks() == null ? "" : String.valueOf(p.clicks()),
                    p.ctrPct() == null ? "" : String.format(Locale.ROOT, "%.2f", p.ctrPct()),
                    String.valueOf(p.orders())));
        }
        return rows;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
