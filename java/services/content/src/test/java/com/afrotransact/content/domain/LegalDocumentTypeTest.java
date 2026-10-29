package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.afrotransact.content.domain.legal.LegalDocument;
import com.afrotransact.content.domain.legal.LegalDocumentType;
import org.junit.jupiter.api.Test;

class LegalDocumentTypeTest {

    private static final Pattern KEBAB = Pattern.compile("[a-z]+(-[a-z]+)*");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    void theLegalCenterHasTwentyOneDocumentsWithUniqueKebabCaseKeys() {
        assertThat(LegalDocumentType.values()).hasSize(21);
        Set<String> keys = Arrays.stream(LegalDocumentType.values()).map(LegalDocumentType::key).collect(Collectors.toSet());
        assertThat(keys).hasSize(21).allMatch(k -> KEBAB.matcher(k).matches())
                .contains("gift-card-terms", "store-credit-terms", "membership-terms", "referral-terms",
                        "preorder-delivery-terms", "community-guidelines", "ip-policy", "provider-terms", "payments-terms",
                        "subprocessors", "data-processing", "seller-fees");
    }

    @Test
    void anUnknownKeyIsNotFoundRatherThanAnException() {
        assertThat(LegalDocumentType.find("gift-card-terms")).contains(LegalDocumentType.GIFT_CARD_TERMS);
        assertThat(LegalDocumentType.find("SELLER_FEES")).contains(LegalDocumentType.SELLER_FEES);
        assertThat(LegalDocumentType.find("terms")).isEmpty();
    }

    @Test
    void translationsMayBeStoredInEveryStorefrontLocale() {
        assertThat(LegalDocument.LOCALES).containsExactlyInAnyOrder("en", "fr", "es", "ar", "yo");
    }

    /** Every document must have a CURRENT version once all migrations ran, or its page would 404 in production. */
    @Test
    void everyDocumentIsSeededByAMigration() throws IOException {
        String sql;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            sql = files.filter(f -> f.getFileName().toString().endsWith(".sql")).map(LegalDocumentTypeTest::read)
                    .collect(Collectors.joining("\n"));
        }
        for (LegalDocumentType type : LegalDocumentType.values()) {
            assertThat(sql).as("seed for %s", type.key()).contains("'" + type.key() + "'");
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
