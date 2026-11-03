package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.catalog.application.ProductImportService;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ImportStatus;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.port.ImageImporter;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The bulk upload end to end against PostgreSQL (S308): template → upload → validation → background import with
 * images through a fake media, partial failure, a crash mid-import resumed without duplicates, and Update mode.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1", "at.catalog.imports.notify-min-rows=3",
    "at.catalog.imports.resume-interval=1h"})
@ActiveProfiles("local")
@Testcontainers
@Import({TestActors.class, ProductImportIT.FakeMedia.class})
class ProductImportIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final AtomicInteger CRASHES = new AtomicInteger();
    private static final AtomicInteger FETCHES = new AtomicInteger();

    private final ProductImportService imports;
    private final ProductService products;
    private final JdbcClient jdbc;

    @Autowired
    ProductImportIT(ProductImportService imports, ProductService products, JdbcClient jdbc) {
        this.imports = imports;
        this.products = products;
        this.jdbc = jdbc;
    }

    /** media-service stand-in: "broken" URLs are refused, "crash" URLs fail like a pod dying (once per counter). */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeMedia {
        @Bean
        @Primary
        ImageImporter fakeImageImporter() {
            return (url, ownerOrg, uploadedBy) -> {
                FETCHES.incrementAndGet();
                if (url.contains("crash") && CRASHES.getAndDecrement() > 0) {
                    throw new IllegalStateException("pod died while copying " + url);
                }
                if (url.contains("broken")) {
                    return new ImageImporter.ImportedImage(null, 0, 0, 0, "", "REJECTED",
                            "The link answered 404 Not Found");
                }
                return new ImageImporter.ImportedImage("https://utfs.io/f/" + Math.abs(url.hashCode()) + ".jpg", 1600,
                        1600, 250_000, "image/jpeg", "READY", null);
            };
        }
    }

    @BeforeEach
    void seller() {
        TestActors.as(new Actor("member-" + org(), PrincipalType.SELLER_MEMBER, Optional.of(org()),
                Set.of("seller.owner"), Set.of("catalog:write")));
    }

    @AfterEach
    void reset() {
        TestActors.reset();
    }

    private String org;

    private String org() {
        if (org == null) {
            org = "org_bulk_" + UUID.randomUUID().toString().substring(0, 8);
        }
        return org;
    }

    /** The dress template's CSV header plus rows. */
    private byte[] dressCsv(String... rows) {
        String template = new String(imports.template("dress", null, SpreadsheetFormat.CSV).content(),
                StandardCharsets.UTF_8);
        return (template + String.join("\r\n", rows) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** One CSV line in the dress template's column order from "column=value" pairs. */
    private String row(String... pairs) {
        String header = new String(imports.template("dress", null, SpreadsheetFormat.CSV).content(),
                StandardCharsets.UTF_8).lines().filter(l -> !l.startsWith("#") && !l.startsWith("\uFEFF#"))
                .findFirst().orElseThrow();
        List<String> cols = List.of(header.split(","));
        String[] cells = new String[cols.size()];
        Arrays.fill(cells, "");
        for (String p : pairs) {
            String key = p.substring(0, p.indexOf('='));
            int i = cols.indexOf(key);
            assertThat(i).as("column " + key).isNotNegative();
            String v = p.substring(p.indexOf('=') + 1);
            cells[i] = v.contains(",") ? "\"" + v + "\"" : v;
        }
        return String.join(",", cells);
    }

    private String dressRow(String parent, String sku, String size, String image) {
        return row("Parent SKU=" + parent, "SKU *=" + sku, "Title *=Ankara Wrap Dress, Puff Sleeve",
                "Brand *=Adire Lagos", "Image URLs *=" + image, "Description=Hand-finished wax print.",
                "Price (USD) *=49.00", "Quantity *=5", "No barcode=Yes", "Variation=" + (size.isEmpty() ? "" : "Size"),
                "Size=" + size, "Material *=Lace", "Fit *=Regular", "Length *=Midi", "Sleeve *=Short",
                "Occasion *=Everyday", "Care *=Cold wash", "Made in *=Nigeria");
    }

    private ProductImport awaitDone(UUID id) {
        Instant until = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(until)) {
            ProductImport u = imports.get(id);
            if (u.status() == ImportStatus.COMPLETED || u.status() == ImportStatus.FAILED) {
                return u;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("import " + id + " did not finish: " + imports.get(id));
    }

    private long variantsWithSku(String sku) {
        return jdbc.sql("select count(*) from product_variant where owner_org = :org and seller_sku = :sku")
                .param("org", org()).param("sku", sku).query(Long.class).single();
    }

    @Test
    void importsTheValidRowsInTheBackgroundAndReportsTheRest() {
        byte[] file = dressCsv(
                dressRow("AWD", "AWD-S", "S", "https://cdn.example.com/awd-front.jpg https://cdn.example.com/broken.jpg"),
                dressRow("AWD", "AWD-M", "M", ""),
                dressRow("", "SOLO-1", "", "https://cdn.example.com/solo.jpg"),
                dressRow("", "BAD-1", "", "https://cdn.example.com/broken-main.jpg"),
                dressRow("", "AWD-S", "", "https://cdn.example.com/dup.jpg"),
                row("SKU *=NOPRICE-1", "Title *=Missing the price column value"));
        ProductImport u = imports.upload("dresses.csv", file, ImportMode.CREATE, "dress", "Ade Okafor");

        assertThat(u.status()).isEqualTo(ImportStatus.VALIDATED);
        assertThat(u.totalRows()).isEqualTo(6);
        assertThat(u.errorRows()).isEqualTo(2);
        assertThat(u.notifySeller()).isTrue();
        assertThat(u.reference()).matches("BU-\\d{6}");
        assertThat(imports.rows(u.id(), RowStatus.ERROR, null, 0, 50).items()).extracting(StoredRow::sku)
                .containsExactly("AWD-S", "NOPRICE-1");

        assertThatThrownBy(() -> imports.start(u.id(), false)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("2 row(s) have errors");
        ProductImport started = imports.start(u.id(), true);
        assertThat(started.status()).isIn(ImportStatus.QUEUED, ImportStatus.RUNNING, ImportStatus.COMPLETED);
        ProductImport done = awaitDone(u.id());

        assertThat(done.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(done.createdProducts()).isEqualTo(2);
        assertThat(done.failedRows()).isEqualTo(1);
        assertThat(done.processedRows()).isEqualTo(4);
        List<StoredRow> rows = imports.rows(u.id(), null, null, 0, 50).items();
        assertThat(rows).extracting(StoredRow::sku, StoredRow::outcome).containsExactly(
                tuple("AWD-S", RowOutcome.CREATED),
                tuple("AWD-M", RowOutcome.CREATED),
                tuple("SOLO-1", RowOutcome.CREATED),
                tuple("BAD-1", RowOutcome.FAILED),
                tuple("AWD-S", RowOutcome.SKIPPED),
                tuple("NOPRICE-1", RowOutcome.SKIPPED));
        assertThat(rows.get(0).outcomeNote()).contains("Image 2 skipped: The link answered 404 Not Found");
        assertThat(rows.get(3).outcomeNote()).contains("Main image https://cdn.example.com/broken-main.jpg");
        Product awd = products.get(rows.get(0).productId());
        assertThat(awd.variants()).hasSize(2);
        assertThat(awd.variationTheme()).isEqualTo("Size");
        assertThat(awd.images()).hasSize(1);
        assertThat(products.get(rows.get(2).productId()).variationTheme()).isEmpty();

        String report = new String(imports.report(u.id(), SpreadsheetFormat.CSV).content(), StandardCharsets.UTF_8);
        assertThat(report).contains("Row status", "Duplicate SKU: also on row", "Created", "Failed: Main image");
        List<String> events = jdbc.sql("select type from platform_outbox where aggregate_id = :id")
                .param("id", u.id().toString()).query(String.class).list();
        assertThat(events).containsExactly("catalog.import.completed");

        // Starting again is harmless: no row is imported twice.
        imports.start(u.id(), true);
        assertThat(variantsWithSku("AWD-S")).isEqualTo(1);
    }

    @Test
    void aCrashMidImportResumesWithoutDuplicates() {
        CRASHES.set(1);
        byte[] file = dressCsv(
                dressRow("", "ONE-1", "", "https://cdn.example.com/one.jpg"),
                dressRow("", "TWO-1", "", "https://cdn.example.com/crash-two.jpg"),
                dressRow("", "THREE-1", "", "https://cdn.example.com/three.jpg"));
        ProductImport u = imports.upload("crash.csv", file, ImportMode.CREATE, "dress", null);
        imports.start(u.id(), false);
        // The job stops at the second product (the "pod" died): the upload stays RUNNING with one product done.
        Instant until = Instant.now().plusSeconds(20);
        while (imports.get(u.id()).processedRows() < 1 && Instant.now().isBefore(until)) {
            Thread.onSpinWait();
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        ProductImport stuck = imports.get(u.id());
        assertThat(stuck.status()).isEqualTo(ImportStatus.RUNNING);
        assertThat(stuck.createdProducts()).isEqualTo(1);

        // Another pod resumes it once it looks stalled; resuming twice never duplicates a product.
        jdbc.sql("update product_import set heartbeat_at = now() - interval '1 hour' where id = :id")
                .param("id", u.id()).update();
        assertThat(imports.resumeStalled()).isGreaterThanOrEqualTo(1);
        ProductImport done = awaitDone(u.id());
        assertThat(done.createdProducts()).isEqualTo(3);
        jdbc.sql("update product_import set status = 'RUNNING', heartbeat_at = now() - interval '1 hour' where id = :id")
                .param("id", u.id()).update();
        imports.resumeStalled();
        assertThat(awaitDone(u.id()).createdProducts()).isEqualTo(3);
        assertThat(List.of("ONE-1", "TWO-1", "THREE-1")).allSatisfy(sku -> assertThat(variantsWithSku(sku)).isEqualTo(1));
    }

    @Test
    void updateModeChangesPriceStockAndAttributesBySku() {
        ProductImport created = imports.upload("new.csv", dressCsv(
                dressRow("", "UPD-1", "", "https://cdn.example.com/upd.jpg")), ImportMode.CREATE, "dress", null);
        imports.start(created.id(), false);
        UUID productId = imports.rows(awaitDone(created.id()).id(), null, null, 0, 5).items().getFirst().productId();

        ProductImport update = imports.upload("prices.csv", dressCsv(
                row("SKU *=upd-1", "Price (USD) *=39.50", "Quantity *=12", "Care *=Hand wash", "Size=M"),
                row("SKU *=GHOST-1", "Price (USD) *=10")), ImportMode.UPDATE, "dress", null);
        assertThat(update.errorRows()).isEqualTo(1);
        assertThat(imports.rows(update.id(), RowStatus.WARNING, null, 0, 5).items()).singleElement()
                .satisfies(r -> assertThat(r.messages().getFirst().message()).isEqualTo("Ignored in Update mode"));
        imports.start(update.id(), true);
        ProductImport done = awaitDone(update.id());
        assertThat(done.updatedProducts()).isEqualTo(1);
        Product p = products.get(productId);
        assertThat(p.variants().getFirst().priceMinor()).isEqualTo(3950);
        assertThat(p.variants().getFirst().quantity()).isEqualTo(12);
        assertThat(p.attributes()).containsEntry("care", "Hand wash").containsEntry("material", "Lace");
        assertThat(p.title()).isEqualTo("Ankara Wrap Dress, Puff Sleeve");
        List<String> events = jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at")
                .param("id", productId.toString()).query(String.class).list();
        assertThat(events).contains("catalog.product.created", "catalog.product.updated",
                "catalog.product.terms_changed");
    }

    @Test
    void templatesAndUploadsAreScopedAndLimited() {
        assertThat(imports.template(null, null, SpreadsheetFormat.XLSX).fileName()).isEqualTo("afrotransact-products.xlsx");
        assertThat(imports.template("grocery", null, SpreadsheetFormat.XLSX).content()).startsWith('P', 'K');
        assertThatThrownBy(() -> imports.upload("products.xls", new byte[] {1, 2}, ImportMode.CREATE, null, null))
                .hasMessageContaining(".xlsx");
        ProductImport mine = imports.upload("mine.csv", dressCsv(dressRow("", "MINE-1", "", "https://cdn.example.com/m.jpg")),
                ImportMode.CREATE, "dress", null);
        TestActors.as(new Actor("member-other", PrincipalType.SELLER_MEMBER, Optional.of("org_other"),
                Set.of("seller.owner"), Set.of("catalog:write")));
        assertThatThrownBy(() -> imports.get(mine.id())).hasMessageContaining("not found");
        assertThat(imports.list(0, 10).items()).isEmpty();
    }
}
