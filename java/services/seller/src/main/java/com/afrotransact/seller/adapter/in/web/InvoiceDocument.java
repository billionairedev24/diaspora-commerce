package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoiceLine;
import com.afrotransact.seller.domain.InvoicePayment;
import com.afrotransact.seller.domain.PlanPricing;

/** A self-contained, printable HTML invoice (the browser's Print › Save as PDF makes the PDF). */
final class InvoiceDocument {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private InvoiceDocument() {
    }

    static String render(Invoice i, String billedTo) {
        StringBuilder b = new StringBuilder(2048);
        b.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Invoice ")
                .append(esc(i.number())).append("</title><style>")
                .append("body{font:14px/1.5 Figtree,system-ui,sans-serif;color:#111;max-width:760px;margin:32px auto;"
                        + "padding:0 16px}h1{font-size:22px;margin:0}table{width:100%;border-collapse:collapse;"
                        + "margin:16px 0}th,td{text-align:left;padding:8px 0;border-bottom:1px solid #e5e5e5}"
                        + "td.n,th.n{text-align:right}.muted{color:#666}.total td{font-weight:600;border-bottom:0}"
                        + ".pill{display:inline-block;padding:2px 10px;border-radius:99px;background:#eee;font-size:12px}")
                .append("</style></head><body>");
        b.append("<h1>AfroTransact invoice ").append(esc(i.number())).append("</h1><p class=\"muted\">")
                .append("Billed to ").append(esc(billedTo)).append(" · Issued ").append(day(i.createdAt()))
                .append(" · <span class=\"pill\">").append(esc(i.status())).append("</span></p>");
        b.append("<p class=\"muted\">Period ").append(day(i.periodStart())).append(" – ")
                .append(day(i.periodEnd() == null ? null : i.periodEnd().minusSeconds(1))).append("</p>");
        b.append("<table><thead><tr><th>Description</th><th class=\"n\">Amount</th></tr></thead><tbody>");
        for (InvoiceLine l : i.lines()) {
            b.append("<tr><td>").append(esc(l.description())).append("</td><td class=\"n\">")
                    .append(PlanPricing.usd(l.amountCents())).append("</td></tr>");
        }
        b.append("<tr class=\"total\"><td>Total due</td><td class=\"n\">").append(PlanPricing.usd(i.amountDueCents()))
                .append("</td></tr></tbody></table>");
        if (!i.payments().isEmpty()) {
            b.append("<h2 style=\"font-size:16px\">Payments</h2><table><tbody>");
            for (InvoicePayment p : i.payments()) {
                b.append("<tr><td>").append(label(p)).append(p.note() == null ? "" : " <span class=\"muted\">· "
                        + esc(p.note()) + "</span>").append("</td><td class=\"muted\">").append(esc(p.status()
                        .toLowerCase(Locale.ROOT))).append("</td><td class=\"n\">").append(PlanPricing.usd(
                        p.amountCents())).append("</td></tr>");
            }
            b.append("</tbody></table>");
        }
        return b.append("<p class=\"muted\">AfroTransact · plan fees are billed in USD.</p></body></html>").toString();
    }

    private static String label(InvoicePayment p) {
        return switch (p.source()) {
            case CREDIT -> "AfroTransact credit";
            case EARNINGS -> "Paid from your sales";
            case CARD -> "Card or bank";
            case WAIVER -> "Waived";
        };
    }

    private static String day(Instant at) {
        return at == null ? "" : DAY.format(at.atZone(ZoneOffset.UTC));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
