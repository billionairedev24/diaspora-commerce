package com.afrotransact.deliveryruns.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.AttributionChannel;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;
import com.afrotransact.deliveryruns.domain.PreorderPackage;
import com.afrotransact.deliveryruns.domain.PreorderSummary;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.WindowDetails;
import org.junit.jupiter.api.Test;

class PreorderCsvTest {

    private static final Instant OPENS = Instant.parse("2026-10-06T14:00:00Z");

    private static PreorderWindow window() {
        WindowDetails d = new WindowDetails("Lubbock Pre-order #7", List.of(AreaKey.parse("US/TX/Lubbock")), OPENS,
                Instant.parse("2026-10-23T04:59:00Z"), LocalDate.parse("2026-10-25"), ZoneId.of("America/Chicago"),
                "Lubbock pre-order is open", "", "", Set.of("Pantry"), true, true, null, null, true, null, null, null,
                null, null);
        return new PreorderWindow(UUID.randomUUID(), "LUB-7", d, Map.of(), false, true, null, OPENS, OPENS, 0);
    }

    private static PreorderOrder order(PreorderWindow w, String number, String note, PreorderOrderStatus status) {
        List<PreorderLine> lines = List.of(new PreorderLine("EGU", "Egusi, ground", "seller-1", 2),
                new PreorderLine("GAR", "Garri", "seller-2", 1));
        return new PreorderOrder("o-" + number, number, w.id(), "c1", "Ada \"Ada\" Okafor", "+1 (806) 555-0101",
                "ada@example.test", new PreorderOrder.Address("1902 19th St", "Lubbock", "TX", "79401"),
                "=HYPERLINK(\"x\")", Instant.parse("2026-10-07T16:30:00Z"), AttributionChannel.INSTAGRAM,
                "src=ig&cr=story&po=LUB-7", List.of(new PreorderPackage("s1", "seller-1", 3799, 799, lines, false)),
                PreorderFulfilment.DOOR, "USD", status, note, OPENS, OPENS, 0);
    }

    @Test
    void ordersExportHasTheDesignColumnsAndEscapesCells() throws IOException {
        PreorderWindow w = window();
        List<List<String>> rows = PreorderCsv.orders(w, List.of(order(w, "LUB-7-1001", null,
                PreorderOrderStatus.PAID), order(w, "LUB-7-L90", "Customer called, by cs-1",
                PreorderOrderStatus.LATE_ADD)));
        assertThat(rows.get(0)).containsExactly("Order", "Pre-order", "Customer", "Phone", "Email", "Street", "City",
                "Region", "Postal code", "Delivery note", "Placed", "Channel", "Tracking tag", "Items", "Units",
                "Fulfilment", "Total", "Status", "Note");
        assertThat(rows.get(1)).containsExactly("LUB-7-1001", "Lubbock Pre-order #7", "Ada \"Ada\" Okafor",
                "+1 (806) 555-0101", "ada@example.test", "1902 19th St", "Lubbock", "TX", "79401",
                "=HYPERLINK(\"x\")", "2026-10-07T16:30:00Z", "Instagram", "src=ig&cr=story&po=LUB-7",
                "Egusi, ground x2; Garri x1", "3", "Door", "37.99", "Paid", "");
        assertThat(rows.get(2).get(17)).isEqualTo("Late add");

        CsvExport export = new CsvExport("LUB-7-orders.csv", rows);
        assertThat(export.size()).isEqualTo(2);
        StringWriter out = new StringWriter();
        export.writeTo(out);
        String[] lines = out.toString().split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).startsWith("LUB-7-1001,Lubbock Pre-order #7,\"Ada \"\"Ada\"\" Okafor\","
                + "+1 (806) 555-0101,");
        assertThat(lines[1]).contains(",\"'=HYPERLINK(\"\"x\"\")\",").contains(",\"Egusi, ground x2; Garri x1\",");
        assertThat(lines[2]).endsWith(",Late add,\"Customer called, by cs-1\"");
    }

    @Test
    void formulaLikeCellsAreNeutralisedButPhoneNumbersAreKept() {
        assertThat(CsvExport.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvExport.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvExport.cell("+cmd|' /C calc'!A0")).isEqualTo("'+cmd|' /C calc'!A0");
        assertThat(CsvExport.cell("+1 (806) 555-0101")).isEqualTo("+1 (806) 555-0101");
        assertThat(CsvExport.cell("-12.50")).isEqualTo("-12.50");
        assertThat(CsvExport.cell(null)).isEmpty();
        assertThat(CsvExport.cell("a\nb")).isEqualTo("\"a\nb\"");
    }

    @Test
    void pickingListAndAdvertorialReport() {
        PreorderWindow w = window();
        List<PreorderOrder> orders = List.of(order(w, "1", null, PreorderOrderStatus.PAID),
                order(w, "2", null, PreorderOrderStatus.PAID), order(w, "3", null, PreorderOrderStatus.CANCELLED));
        List<List<String>> pick = PreorderCsv.pickingList(PreorderSummary.productUnits(orders));
        assertThat(pick).containsExactly(List.of("Product", "Seller", "Units"), List.of("Egusi, ground", "seller-1",
                "4"), List.of("Garri", "seller-2", "2"));
        List<List<String>> report = PreorderCsv.advertorialReport(PreorderSummary.of(w, orders,
                Instant.parse("2026-10-10T00:00:00Z")));
        assertThat(report.get(0)).containsExactly("Placement", "Creative", "Impressions", "Clicks", "CTR %",
                "Pre-orders");
        assertThat(report).hasSize(7);
        assertThat(report.get(1)).containsExactly("Storefront hero", "no", "", "", "", "0");
        assertThat(report.get(2)).containsExactly("Header ribbon (text)", "yes", "", "", "", "0");
        assertThat(report.get(4)).containsExactly("Social story", "no", "", "", "", "2");
    }

    @Test
    void exportKindsMapToFileNames() {
        assertThat(ExportKind.fromPath("orders-filtered")).isEqualTo(ExportKind.ORDERS_FILTERED);
        assertThat(ExportKind.PICKING_LIST.path()).isEqualTo("picking-list");
    }
}
