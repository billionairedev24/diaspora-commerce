package com.afrotransact.retail.adapter.out.edi;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.retail.domain.Currencies;
import com.afrotransact.retail.domain.PoLine;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.port.PurchaseOrderDocuments;

/**
 * ANSI X12 004010 850 purchase order: ISA/GS envelope, BEG, CUR, ITD terms, DTM delivery date, N1 buyer/supplier/
 * ship-to, one PO1 per line (UP = GTIN-12, VP = supplier SKU, SK = our SKU) with PID description, CTT and SE/GE/IEA
 * trailers. Segment terminator {@code ~}, element separator {@code *}.
 */
public class X12Edi850Writer implements PurchaseOrderDocuments {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter CCYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

    private final String senderId;
    private final Clock clock;

    public X12Edi850Writer(String senderId, Clock clock) {
        this.senderId = senderId;
        this.clock = clock;
    }

    @Override
    public String edi850(PurchaseOrder po, Supplier supplier, Map<UUID, RetailItem> items) {
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        String control = String.format(Locale.ROOT, "%09d", Math.abs(po.number().hashCode()) % 1_000_000_000);
        List<String> st = new ArrayList<>();
        st.add("ST*850*0001");
        st.add("BEG*00*SA*" + po.number() + "**" + CCYYMMDD.format(po.orderDate()));
        st.add("CUR*BY*" + po.currency() + "*" + po.fx().unitsPerBase().toPlainString());
        if (supplier.paymentTerms() != null) {
            st.add("ITD*01*3*******" + clean(supplier.paymentTerms()));
        }
        st.add("FOB*PB***01*" + clean(po.incoterm()));
        if (po.expectedDate() != null) {
            st.add("DTM*002*" + CCYYMMDD.format(po.expectedDate()));
        }
        st.add("N1*BY*AfroTransact Retail*92*" + senderId);
        st.add("N1*SE*" + clean(supplier.name()) + "*92*" + supplier.code());
        st.add("N1*ST*" + po.destinationNodeId() + "*92*" + po.destinationNodeId());
        for (PoLine l : po.lines()) {
            RetailItem item = items.get(l.itemId());
            BigDecimal price = Currencies.toMajor(l.unitCostMinor(), po.currency());
            st.add("PO1*" + l.lineNo() + "*" + l.qtyOrdered() + "*EA*" + price.toPlainString() + "*PE"
                    + (item == null ? "" : "*UP*" + item.gtin()) + "*VP*" + clean(l.supplierSku()) + "*SK*" + l.sku());
            st.add("PID*F****" + clean(l.description()));
        }
        st.add("CTT*" + po.lines().size() + "*" + po.lines().stream().mapToInt(PoLine::qtyOrdered).sum());
        st.add("SE*" + (st.size() + 1) + "*0001");
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "ISA*00*          *00*          *ZZ*%-15s*ZZ*%-15s*%s*%s*U*00401*%s*0*P*>~",
                senderId, supplier.code(), YYMMDD.format(now), HHMM.format(now), control));
        out.append("GS*PO*").append(senderId).append('*').append(supplier.code()).append('*')
                .append(CCYYMMDD.format(now)).append('*').append(HHMM.format(now)).append("*1*X*004010~");
        st.forEach(s -> out.append(s).append('~'));
        out.append("GE*1*1~");
        out.append("IEA*1*").append(control).append('~');
        return out.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[*~>]", " ");
    }
}
