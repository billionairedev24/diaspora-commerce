package com.afrotransact.catalog.domain.barcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.testsupport.BarcodeScanner;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** S032: Code 128, GS1-128 and EAN-13 encodings, checked codeword by codeword and by scanning them with ZXing. */
class BarcodeEncoderTest {

    private final BarcodeEncoder encoder = new DefaultBarcodeEncoder();

    @Test
    void gs1CheckDigitsForEveryGtinLengthAndSscc() {
        assertThat(Gtin.checkDigit("9638507")).isEqualTo(4);
        assertThat(Gtin.checkDigit("03600029145")).isEqualTo(2);
        assertThat(Gtin.checkDigit("950110153000")).isEqualTo(3);
        assertThat(Gtin.checkDigit("1061414100041")).isEqualTo(5);
        assertThat(Gtin.isValidMod10("106141411234567897")).isTrue();
        assertThat(Gtin.isValidMod10("106141411234567890")).isFalse();
        assertThat(new Gtin("036000291452").toGtin14()).isEqualTo("00036000291452");
        assertThat(new Gtin("96385074").toGtin14()).isEqualTo("00000096385074");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATF3XK8P2QL", "AT0K7Q2M9H", "MAMT-OFR-5LB", "HOU-A12-03-B", "LPN8QZ4T1MW", "12345678",
        "ab 1234567 cd", "x"})
    void code128ScansBackToTheSameText(String text) {
        BarcodeSymbol symbol = encoder.code128(text);
        assertThat(symbol.modules()).startsWith("11").endsWith("1100011101011");
        assertThat(symbol.modules().length()).isEqualTo((symbol.codewords().size()) * 11 + 13);
        Result scanned = BarcodeScanner.scan(symbol, BarcodeFormat.CODE_128);
        assertThat(scanned.getText()).isEqualTo(text);
    }

    @Test
    void code128UsesCodeSetCForDigitRuns() {
        // All digits, even count: start C, two digits per codeword.
        assertThat(encoder.code128("12345678").codewords()).containsExactly(105, 12, 34, 56, 78, 47);
        // Letters then six digits: start B, switch to C for the digits.
        List<Integer> cw = encoder.code128("ATF123456").codewords();
        assertThat(cw.subList(0, cw.size() - 1)).containsExactly(104, 33, 52, 38, 99, 12, 34, 56);
        // An odd run is entered after one digit in B.
        List<Integer> odd = encoder.code128("A12345").codewords();
        assertThat(odd.subList(0, odd.size() - 1)).containsExactly(104, 33, 17, 99, 23, 45);
    }

    @Test
    void gs1128StartsWithFnc1AndSeparatesOnlyVariableLengthFieldsThatAreNotLast() {
        Map<ApplicationIdentifier, String> ai = new EnumMap<>(ApplicationIdentifier.class);
        ai.put(ApplicationIdentifier.SERIAL, "SER1");
        ai.put(ApplicationIdentifier.BATCH_LOT, "LOT42");
        ai.put(ApplicationIdentifier.EXPIRY, "261231");
        ai.put(ApplicationIdentifier.GTIN, "09501101530003");
        Gs1ElementString elements = Gs1ElementString.of(ai);

        // Predefined-length AIs first, variable-length last.
        assertThat(elements.humanReadable()).isEqualTo("(01)09501101530003(17)261231(10)LOT42(21)SER1");
        assertThat(elements.transmitted()).isEqualTo("0109501101530003172612311" + "0LOT42\u001d21SER1");
        int[] input = elements.carrierInput();
        assertThat(input[0]).isEqualTo(Code128.FNC1);
        assertThat(input[input.length - 1]).as("no FNC1 after the last field").isEqualTo('1');
        assertThat(Arrays.stream(input).filter(c -> c == Code128.FNC1).count()).isEqualTo(2);

        BarcodeSymbol symbol = encoder.gs1128(elements);
        assertThat(symbol.codewords()).containsExactly(
                105, 102,                                // start C, FNC1 (GS1-128)
                1, 9, 50, 11, 1, 53, 0, 3,               // 01 09501101530003
                17, 26, 12, 31,                          // 17 261231
                10, 100, 44, 47, 52, 20, 18,             // 10, code B, LOT42
                102,                                     // FNC1 separator after the variable-length lot
                18, 17, 51, 37, 50, 17,                  // 21SER1
                61);                                     // mod 103 check
        Result scanned = BarcodeScanner.scan(symbol, BarcodeFormat.CODE_128);
        assertThat(scanned.getText()).isEqualTo("]C1" + elements.transmitted());
    }

    @Test
    void gs1128WithOnlyFixedLengthFieldsNeedsNoSeparator() {
        Map<ApplicationIdentifier, String> ai = new EnumMap<>(ApplicationIdentifier.class);
        ai.put(ApplicationIdentifier.GTIN, "09501101530003");
        ai.put(ApplicationIdentifier.PRODUCTION_DATE, "261001");
        ai.put(ApplicationIdentifier.EXPIRY, "270331");
        Gs1ElementString elements = Gs1ElementString.of(ai);
        assertThat(elements.carrierInput()).containsOnlyOnce(Code128.FNC1);
        assertThat(BarcodeScanner.scan(encoder.gs1128(elements), BarcodeFormat.CODE_128).getText())
                .isEqualTo("]C1010950110153000311261001" + "17270331");
    }

    @Test
    void gs1ValidationNamesTheBrokenAi() {
        Map<ApplicationIdentifier, String> ai = new EnumMap<>(ApplicationIdentifier.class);
        ai.put(ApplicationIdentifier.GTIN, "09501101530004");
        ai.put(ApplicationIdentifier.BATCH_LOT, "LOT#1");
        ai.put(ApplicationIdentifier.EXPIRY, "261331");
        ai.put(ApplicationIdentifier.COUNT, "12");
        assertThatThrownBy(() -> Gs1ElementString.of(ai))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys("ai01", "ai10", "ai17", "ai37"));
    }

    @Test
    void gs1128RejectsMoreThan48Characters() {
        Map<ApplicationIdentifier, String> ai = new EnumMap<>(ApplicationIdentifier.class);
        ai.put(ApplicationIdentifier.GTIN, "09501101530003");
        ai.put(ApplicationIdentifier.BATCH_LOT, "L".repeat(20));
        ai.put(ApplicationIdentifier.SERIAL, "S".repeat(20));
        assertThatThrownBy(() -> Gs1ElementString.of(ai)).hasMessageContaining("48");
    }

    @Test
    void ean13AndUpcAScan() {
        BarcodeSymbol ean = encoder.ean13("4006381333931");
        assertThat(ean.modules()).hasSize(95).startsWith("101").endsWith("101");
        assertThat(BarcodeScanner.scan(ean, BarcodeFormat.EAN_13).getText()).isEqualTo("4006381333931");

        BarcodeSymbol upc = encoder.ean13("036000291452");
        assertThat(upc.data()).isEqualTo("0036000291452");
        assertThat(BarcodeScanner.scan(upc, BarcodeFormat.EAN_13).getText()).isEqualTo("0036000291452");

        assertThatThrownBy(() -> encoder.ean13("4006381333932")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.ean13("96385074")).isInstanceOf(IllegalArgumentException.class);
    }
}
