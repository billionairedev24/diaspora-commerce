package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class GtinReaderTest {

    private static final List<String> RESERVED = List.of("02", "04", "20", "21", "22", "23", "24", "25", "26", "27",
            "28", "29", "981", "982", "983", "984", "99");

    @Test
    void aUpcAndTheEanOfTheSameItemShareOneKey() {
        GtinReader.Reading upc = GtinReader.read("012345678905", RESERVED);
        GtinReader.Reading ean = GtinReader.read("0012345678905", RESERVED);
        GtinReader.Reading gtin14 = GtinReader.read("00012345678905", RESERVED);

        assertThat(upc.valid()).isTrue();
        assertThat(upc.gtin14()).isEqualTo("00012345678905").isEqualTo(ean.gtin14()).isEqualTo(gtin14.gtin14());
    }

    @Test
    void spacesAndDashesAreDropped() {
        assertThat(GtinReader.read(" 4006-3813 33931 ", RESERVED).gtin14()).isEqualTo("04006381333931");
    }

    @Test
    void anIsbn10BecomesTheIsbn13BeforeMatching() {
        GtinReader.Reading isbn10 = GtinReader.read("0-306-40615-2", RESERVED);
        GtinReader.Reading isbn13 = GtinReader.read("978-0-306-40615-7", RESERVED);

        assertThat(isbn10.valid()).isTrue();
        assertThat(isbn10.digits()).isEqualTo("9780306406157");
        assertThat(isbn10.gtin14()).isEqualTo(isbn13.gtin14()).isEqualTo("09780306406157");
    }

    @Test
    void anIsbn10EndingInXIsConverted() {
        // 0-8044-2957-X is a valid ISBN-10 (the check "digit" is 10).
        GtinReader.Reading reading = GtinReader.read("0-8044-2957-x", RESERVED);

        assertThat(reading.valid()).isTrue();
        assertThat(reading.digits()).isEqualTo("9780804429573");
    }

    @Test
    void anIsbn10WithABadCheckDigitIsInvalid() {
        GtinReader.Reading reading = GtinReader.read("0-306-40615-3", RESERVED);

        assertThat(reading.status()).isEqualTo(GtinReader.Status.INVALID);
        assertThat(reading.message()).contains("does not look right");
    }

    @Test
    void aWrongCheckDigitSuggestsTheRightCodeInPlainWords() {
        GtinReader.Reading reading = GtinReader.read("4006381333932", RESERVED);

        assertThat(reading.status()).isEqualTo(GtinReader.Status.INVALID);
        assertThat(reading.suggestion()).isEqualTo("4006381333931");
        assertThat(reading.message()).isEqualTo("That barcode does not look right. Did you mean 4006381333931?");
        assertThat(reading.gtin14()).isNull();
    }

    @Test
    void aWrongLengthIsInvalidWithoutASuggestion() {
        GtinReader.Reading reading = GtinReader.read("12345", RESERVED);

        assertThat(reading.status()).isEqualTo(GtinReader.Status.INVALID);
        assertThat(reading.suggestion()).isNull();
        assertThat(reading.message()).contains("Use 8, 12, 13 or 14 digits");
    }

    @Test
    void nothingTypedIsEmpty() {
        assertThat(GtinReader.read("  ", RESERVED).status()).isEqualTo(GtinReader.Status.EMPTY);
        assertThat(GtinReader.read(null, RESERVED).status()).isEqualTo(GtinReader.Status.EMPTY);
    }

    @Test
    void storeUseAndCouponPrefixesAreRestrictedWithAPlainMessage() {
        // In-store numbers: UPC-A starting 2 or 4 is EAN 02 / 04; EAN 20 to 29; coupons 981 to 984 and 99.
        for (String code : List.of("0212345678905", "2012345678903", "2912345678908", "9811234567891", "9841234567892",
                "9901234567890", "212345678908", "412345678900")) {
            String full = withCheckDigit(code);
            GtinReader.Reading reading = GtinReader.read(full, RESERVED);
            assertThat(reading.status()).as(full).isEqualTo(GtinReader.Status.RESTRICTED);
            assertThat(reading.message()).isEqualTo("This number is for store use or coupons, not a product barcode.");
            assertThat(reading.gtin14()).isNotNull();
        }
    }

    @Test
    void aRestrictedNumberIsOnlyRestrictedWhenItsPrefixIsInTheStaffList() {
        String code = withCheckDigit("2012345678903");

        assertThat(GtinReader.read(code, RESERVED).status()).isEqualTo(GtinReader.Status.RESTRICTED);
        assertThat(GtinReader.read(code, List.of()).status()).isEqualTo(GtinReader.Status.VALID);
        assertThat(GtinReader.read(code, List.of("20")).status()).isEqualTo(GtinReader.Status.RESTRICTED);
    }

    @Test
    void ordinaryRetailPrefixesAreNotRestricted() {
        // 978 books, 400 Germany, 500 UK, 0 US: none starts with a reserved prefix.
        for (String code : List.of("9780306406157", "4006381333931", "5012345678900", "012345678905", "036000291452")) {
            assertThat(GtinReader.read(code, RESERVED).status()).as(code).isEqualTo(GtinReader.Status.VALID);
        }
    }

    private static String withCheckDigit(String partial) {
        // The first 12 (EAN-13) or 11 (UPC-A) digits plus the GS1 check digit.
        String body = partial.length() == 13 ? partial.substring(0, 12) : partial.substring(0, partial.length() - 1);
        return body + Gtin.checkDigit(body);
    }
}
