package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** S395: the rows of a stock file are checked before anything is stored. */
class StockImportValidatorTest {

    private static final Set<String> NODES = Set.of("HOU-FC", "EWR-FC");
    private static final Set<String> HELD = Set.of("SKU-1|HOU-FC", "SKU-2|HOU-FC", "SKU-3|EWR-FC");

    private static List<String> header() {
        return List.of("Seller SKU", "Location", "On hand", "Lot", "Expiry");
    }

    private static StockImportValidator.Result validate(StockImportMode mode, List<String>... rows) {
        List<List<String>> records = new ArrayList<>();
        records.add(header());
        records.addAll(List.of(rows));
        return StockImportValidator.validate(records, mode, NODES, HELD, StockImportValidator.MAX_ROWS);
    }

    @SafeVarargs
    private static List<List<String>> records(List<String>... rows) {
        List<List<String>> records = new ArrayList<>();
        records.add(header());
        records.addAll(List.of(rows));
        return records;
    }

    @Test
    void aGoodRowIsValidWithItsTypedValues() {
        StockImportValidator.Result r = validate(StockImportMode.ABSOLUTE,
                List.of("sku-1", "hou-fc", "120", "lot-a", "2027-03-31"));

        StockImportRow row = r.rows().getFirst();
        assertThat(row.status()).isEqualTo("VALID");
        assertThat(row.sku()).isEqualTo("SKU-1");
        assertThat(row.nodeId()).isEqualTo("HOU-FC");
        assertThat(row.lotNo()).isEqualTo("LOT-A");
        assertThat(row.quantity()).isEqualTo(120);
        assertThat(row.expiry()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(row.outcome()).isEqualTo(StockRowOutcome.PENDING);
        assertThat(row.rowNumber()).isEqualTo(2);
    }

    @Test
    void aSkuTheSellerDoesNotHoldIsAnUnknownOneWhoeverOwnsIt() {
        StockImportValidator.Result r = validate(StockImportMode.ABSOLUTE, List.of("OTHER-SELLERS-SKU", "HOU-FC", "5", "", ""),
                List.of("SKU-3", "HOU-FC", "5", "", ""));

        assertThat(r.rows()).extracting(StockImportRow::status).containsExactly("ERROR", "ERROR");
        assertThat(r.rows().get(0).summary()).isEqualTo(
                "We do not hold OTHER-SELLERS-SKU for you at HOU-FC. Check the SKU and the location");
        assertThat(r.rows().get(1).summary()).isEqualTo("We do not hold SKU-3 for you at HOU-FC. Check the SKU and the location");
    }

    @Test
    void anUnknownLocationNegativeAndOverLimitQuantitiesAreRowErrors() {
        StockImportValidator.Result r = validate(StockImportMode.ABSOLUTE,
                List.of("SKU-1", "MARS-FC", "5", "", ""), List.of("SKU-1", "HOU-FC", "-1", "", ""),
                List.of("SKU-2", "HOU-FC", "100000", "", ""), List.of("SKU-2", "HOU-FC", "abc", "", ""),
                List.of("SKU-2", "HOU-FC", "", "", ""), List.of("SKU-2", "HOU-FC", "5", "", "31/03/2027"));

        List<String> messages = r.rows().stream().map(StockImportRow::summary).toList();
        assertThat(messages.get(0)).startsWith("MARS-FC is not a location we know");
        assertThat(messages.get(1)).isEqualTo("Stock cannot be negative");
        assertThat(messages.get(2)).isEqualTo("At most 99999 units");
        assertThat(messages.get(3)).isEqualTo("A whole number");
        assertThat(messages.get(4)).isEqualTo("Give the quantity on hand");
        assertThat(messages.get(5)).isEqualTo("Write the date as year-month-day, for example 2027-03-31");
        assertThat(r.count("ERROR")).isEqualTo(6);
    }

    @Test
    void aChangeMayBeNegativeInDeltaMode() {
        StockImportValidator.Result r = validate(StockImportMode.DELTA, List.of("SKU-1", "HOU-FC", "-3", "", ""),
                List.of("SKU-2", "HOU-FC", "+5", "", ""), List.of("SKU-1", "EWR-FC", "-100000", "", ""));

        assertThat(r.rows().get(0).quantity()).isEqualTo(-3);
        assertThat(r.rows().get(1).quantity()).isEqualTo(5);
        assertThat(r.rows().get(2).status()).isEqualTo("ERROR");
    }

    @Test
    void theSameSkuLocationAndLotTwiceFirstRowWins() {
        StockImportValidator.Result r = validate(StockImportMode.ABSOLUTE, List.of("SKU-1", "HOU-FC", "5", "A", ""),
                List.of("SKU-1", "HOU-FC", "9", "A", ""), List.of("SKU-1", "HOU-FC", "9", "B", ""));

        assertThat(r.rows()).extracting(StockImportRow::status).containsExactly("VALID", "ERROR", "VALID");
        assertThat(r.rows().get(1).summary()).startsWith("This SKU, location and lot appear twice in your file. Row 2 is used");
    }

    @Test
    void headersAreMatchedByNameInAnyOrderWithTheNamesSellersUse() {
        List<List<String>> records = List.of(List.of("# a comment line"), List.of("Expiry date", "Counted", "SKU", "Node"),
                List.of("", "7", "sku-2", "hou-fc"));

        StockImportValidator.Result r = StockImportValidator.validate(records, StockImportMode.ABSOLUTE, NODES, HELD, 10);

        assertThat(r.rows().getFirst().status()).isEqualTo("VALID");
        assertThat(r.rows().getFirst().quantity()).isEqualTo(7);
        assertThat(r.rows().getFirst().rowNumber()).isEqualTo(3);
    }

    @Test
    void aFileWithoutTheRequiredColumnsOrRowsIsRefused() {
        assertThatThrownBy(() -> StockImportValidator.validate(List.of(List.of("Seller SKU", "On hand"), List.of("A", "1")),
                StockImportMode.ABSOLUTE, NODES, HELD, 10)).isInstanceOf(InventoryRuleViolation.class)
                .hasMessageContaining("no Location column");
        assertThatThrownBy(() -> StockImportValidator.validate(List.of(header()), StockImportMode.ABSOLUTE, NODES, HELD, 10))
                .isInstanceOf(InventoryRuleViolation.class).hasMessageContaining("no rows under the header");
        assertThatThrownBy(() -> StockImportValidator.validate(List.of(header(), List.of("SKU-1", "HOU-FC", "1"),
                List.of("SKU-2", "HOU-FC", "1")), StockImportMode.ABSOLUTE, NODES, HELD, 1))
                .isInstanceOf(InventoryRuleViolation.class).hasMessageContaining("upload at most 1 rows");
    }

    @Test
    void fiftyThousandRowsAreAccepted() {
        List<List<String>> records = new ArrayList<>();
        records.add(header());
        Set<String> held = new HashSet<>();
        for (int i = 0; i < StockImportValidator.MAX_ROWS; i++) {
            records.add(List.of("S" + i, "HOU-FC", "1", "", ""));
            held.add("S" + i + "|HOU-FC");
        }

        StockImportValidator.Result r = StockImportValidator.validate(records, StockImportMode.ABSOLUTE, NODES, held,
                StockImportValidator.MAX_ROWS);

        assertThat(r.rows()).hasSize(50_000);
        assertThat(r.count("ERROR")).isZero();
        assertThat(StockImportValidator.skusOf(records)).hasSize(50_000);
    }

    @Test
    void csvReadsQuotesSemicolonsAndByteOrderMarksAndRefusesAnUnclosedQuote() {
        byte[] file = "﻿Seller SKU;Location;On hand\r\n\"SKU,1\";HOU-FC;\"5\"\r\n".getBytes(StandardCharsets.UTF_8);

        List<List<String>> parsed = StockCsv.parse(file, 100);

        assertThat(parsed).containsExactly(List.of("Seller SKU", "Location", "On hand"), List.of("SKU,1", "HOU-FC", "5"));
        assertThatThrownBy(() -> StockCsv.parse("a,\"b\n".getBytes(StandardCharsets.UTF_8), 10))
                .isInstanceOf(InventoryRuleViolation.class).hasMessageContaining("never closed");
        assertThatThrownBy(() -> StockCsv.parse("a\nb\nc\n".getBytes(StandardCharsets.UTF_8), 2))
                .isInstanceOf(InventoryRuleViolation.class);
    }

    @Test
    void theReportNeutralisesFormulas() {
        String csv = new String(StockCsv.write(List.of(List.of("=HYPERLINK(\"x\")", "-3", "@SUM(1)", "ok, fine"))),
                StandardCharsets.UTF_8);

        assertThat(csv).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\",'-3,'@SUM(1),\"ok, fine\"\r\n");
    }
}
