package com.afrotransact.retail.adapter.out.persistence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.afrotransact.retail.port.ReferenceIssuer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Document numbers from PostgreSQL sequences, in the design's formats. */
@Component
class JdbcReferenceIssuer implements ReferenceIssuer {

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");

    private final JdbcClient jdbc;

    JdbcReferenceIssuer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextPurchaseOrderNumber() {
        return "PO-1P-" + next("po_number_seq");
    }

    @Override
    public String nextShipmentReference(String originCountry, LocalDate date) {
        String country = originCountry == null ? "XX" : originCountry.toUpperCase(Locale.ROOT);
        return String.format(Locale.ROOT, "SHP-%s-%s%02d", YYMM.format(date), country, next("shipment_seq"));
    }

    @Override
    public String nextReceiptNumber(LocalDate date) {
        return String.format(Locale.ROOT, "GRN-%s-%04d", YYMM.format(date), next("receipt_seq"));
    }

    @Override
    public String nextJournalNumber() {
        return "JE-" + next("journal_seq");
    }

    @Override
    public long nextItemReference() {
        return next("item_reference_seq");
    }

    private long next(String sequence) {
        return jdbc.sql("select nextval('" + sequence + "')").query(Long.class).single();
    }
}
