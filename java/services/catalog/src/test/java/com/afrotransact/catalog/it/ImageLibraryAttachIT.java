package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.afrotransact.catalog.application.ImageLibraryService;
import com.afrotransact.catalog.application.ImageLibraryService.AttachResult;
import com.afrotransact.catalog.application.ProductImportService;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.imports.ImageLibrary;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ImportStatus;
import com.afrotransact.catalog.domain.imports.LibraryImage;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowImage;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.port.ImageImporter;
import com.afrotransact.catalog.port.ImageLibraryLookup;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * S310 image library against PostgreSQL: the import preview matches library images to rows by SKU and slot (with a
 * fake media-service that serves the organisation's library), and "attach matching images" puts SKU.MAIN / SKU.PT01…
 * on existing listings, skips held images and main images under 1000 px, and is idempotent.
 * {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs it on that PostgreSQL instead of a container.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1", "at.catalog.imports.resume-interval=1h"})
@ActiveProfiles("local")
@Import({TestActors.class, ImageLibraryAttachIT.FakeMedia.class})
class ImageLibraryAttachIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = EXTERNAL_DB == null ? PostgresTestContainer.create() : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
    }

    /** The library media-service would return, per organisation (the tests set it before each call). */
    private static final List<LibraryImage> LIBRARY = new CopyOnWriteArrayList<>();

    private final ProductImportService imports;
    private final ProductService products;
    private final ImageLibraryService library;

    @Autowired
    ImageLibraryAttachIT(ProductImportService imports, ProductService products, ImageLibraryService library) {
        this.imports = imports;
        this.products = products;
        this.library = library;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeMedia {
        @Bean
        @Primary
        ImageImporter fakeImageImporter() {
            return (url, ownerOrg, uploadedBy) -> new ImageImporter.ImportedImage(
                    "https://utfs.io/f/" + Math.abs(url.hashCode()) + ".jpg", 1600, 1600, 250_000, "image/jpeg",
                    "READY", null);
        }

        @Bean
        @Primary
        ImageLibraryLookup fakeLibrary() {
            return (ownerOrg, skus, fileNames) -> {
                Set<String> wantedSkus = new HashSet<>();
                skus.forEach(s -> wantedSkus.add(s.toUpperCase(Locale.ROOT)));
                List<LibraryImage> found = LIBRARY.stream()
                        .filter(i -> wantedSkus.contains(i.sku()) || fileNames.stream()
                                .anyMatch(f -> f.equalsIgnoreCase(i.fileName())))
                        .toList();
                return new ImageLibrary(found);
            };
        }
    }

    private String org;

    @BeforeEach
    void seller() {
        org = "org_lib_" + UUID.randomUUID().toString().substring(0, 8);
        LIBRARY.clear();
        TestActors.as(new Actor("member-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org),
                Set.of("seller.owner"), Set.of("catalog:write")));
    }

    @AfterEach
    void reset() {
        TestActors.reset();
        LIBRARY.clear();
    }

    private static LibraryImage image(String sku, String slot, String status, int edge) {
        String name = sku + "." + slot + ".jpg";
        return new LibraryImage("ast_" + UUID.randomUUID().toString().substring(0, 8), name, sku, slot, status,
                "READY".equals(status) ? "https://utfs.io/f/" + name : null, edge, edge, 200_000, "image/jpeg");
    }

    private byte[] dressCsv(String... rows) {
        String template = new String(imports.template("dress", null, SpreadsheetFormat.CSV).content(),
                StandardCharsets.UTF_8);
        return (template + String.join("\r\n", rows) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private String row(String... pairs) {
        String header = new String(imports.template("dress", null, SpreadsheetFormat.CSV).content(),
                StandardCharsets.UTF_8).lines().filter(l -> !l.startsWith("#") && !l.startsWith("﻿#"))
                .findFirst().orElseThrow();
        List<String> cols = List.of(header.split(","));
        String[] cells = new String[cols.size()];
        Arrays.fill(cells, "");
        for (String p : pairs) {
            String key = p.substring(0, p.indexOf('='));
            int i = cols.indexOf(key);
            assertThat(i).as("column " + key + " in " + cols).isNotNegative();
            String v = p.substring(p.indexOf('=') + 1);
            cells[i] = v.contains(",") ? "\"" + v + "\"" : v;
        }
        return String.join(",", cells);
    }

    /** A dress row; {@code images} are the template's Main image URL and Other image URL 1…8 columns, in order. */
    private String dressRow(String parent, String sku, String size, String... images) {
        List<String> pairs = new ArrayList<>(List.of("Parent SKU=" + parent, "SKU=" + sku,
                "Title *=Ankara Wrap Dress, Puff Sleeve", "Brand or label *=Adire Lagos"));
        for (int i = 0; i < images.length; i++) {
            pairs.add((i == 0 ? "Main image URL=" : "Other image URL " + i + "=") + images[i]);
        }
        pairs.addAll(List.of("Description=Hand-finished wax print.",
                "Price (USD) *=49.00", "Quantity *=5", "No barcode=Yes", "Variation=" + (size.isEmpty() ? "" : "Size"),
                "Size=" + size, "Material=Lace", "Fit=Regular", "Length=Midi",
                "Occasion=Everyday", "Care=Cold wash", "Made in=Nigeria"));
        return row(pairs.toArray(String[]::new));
    }

    private ProductImport awaitDone(UUID id) throws InterruptedException {
        Instant until = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(until)) {
            ProductImport u = imports.get(id);
            if (u.status() == ImportStatus.COMPLETED || u.status() == ImportStatus.FAILED) {
                return u;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("import " + id + " did not finish: " + imports.get(id));
    }

    @Test
    void thePreviewMatchesLibraryImagesToRowsBySkuAndSlot() {
        LIBRARY.addAll(List.of(
                image("PRV-1", "MAIN", "READY", 1600), image("PRV-1", "PT01", "READY", 1600),
                image("PRV-1", "PT02", "READY", 1600), image("PRV-1", "SWCH", "READY", 300),
                image("PRV-HELD", "MAIN", "FLAGGED", 1600),
                image("PRV-PARENT", "MAIN", "READY", 1600),
                image("PRV-4", "MAIN", "READY", 1600)));
        byte[] file = dressCsv(
                dressRow("", "PRV-1", ""),
                dressRow("", "PRV-HELD", ""),
                dressRow("PRV-PARENT", "PRV-PARENT-S", "S"),
                dressRow("", "PRV-NONE", "", "PRV-4.MAIN.jpg", "gone.jpg", "https://cdn.example.com/x.jpg", "not-a-link"));
        ProductImport u = imports.upload("lib.csv", file, ImportMode.CREATE, "dress", null);

        List<StoredRow> rows = imports.rows(u.id(), null, null, 0, 20).items();
        Map<Integer, List<RowImage>> byRow = imports.rowImages(u.id(), rows);
        assertThat(rows).extracting(StoredRow::sku).containsExactly("PRV-1", "PRV-HELD", "PRV-PARENT-S", "PRV-NONE");

        // empty image cell: the library files named after the SKU, one per slot, the swatch last
        List<RowImage> bySku = byRow.get(rows.get(0).rowNumber());
        assertThat(bySku).extracting(RowImage::slot, RowImage::source, RowImage::state, RowImage::ref).containsExactly(
                tuple("MAIN", "SKU", "FOUND", "PRV-1.MAIN.jpg"), tuple("PT01", "SKU", "FOUND", "PRV-1.PT01.jpg"),
                tuple("PT02", "SKU", "FOUND", "PRV-1.PT02.jpg"), tuple("SWCH", "SKU", "FOUND", "PRV-1.SWCH.jpg"));
        assertThat(bySku.getFirst().url()).isEqualTo("https://utfs.io/f/PRV-1.MAIN.jpg");
        // a held image is not used
        assertThat(byRow.get(rows.get(1).rowNumber())).singleElement().satisfies(r -> {
            assertThat(r.state()).isEqualTo("HELD");
            assertThat(r.url()).isNull();
        });
        // a variant without its own images falls back to its Parent SKU
        assertThat(byRow.get(rows.get(2).rowNumber())).extracting(RowImage::ref).containsExactly("PRV-PARENT.MAIN.jpg");
        // the cell's entries go by position: a library file, a file that is not there, a link, a bad entry
        assertThat(byRow.get(rows.get(3).rowNumber())).extracting(RowImage::slot, RowImage::source, RowImage::state)
                .containsExactly(tuple("MAIN", "FILE", "FOUND"), tuple("PT01", "FILE", "MISSING"),
                        tuple("PT02", "LINK", "LINK"), tuple("PT03", "LINK", "BAD_LINK"));
    }

    @Test
    void attachPutsLibraryImagesOnExistingListingsAndIsIdempotent() throws InterruptedException {
        ProductImport created = imports.upload("new.csv", dressCsv(
                dressRow("", "ATT-1", "", "https://cdn.example.com/old-1.jpg"),
                dressRow("", "ATT-2", "", "https://cdn.example.com/old-2.jpg"),
                dressRow("", "ATT-3", "", "https://cdn.example.com/old-3.jpg"),
                dressRow("", "ATT-4", "", "https://cdn.example.com/old-4.jpg")), ImportMode.CREATE, "dress", null);
        imports.start(created.id(), false);
        awaitDone(created.id());
        Map<String, UUID> ids = new HashMap<>();
        for (StoredRow r : imports.rows(created.id(), null, null, 0, 10).items()) {
            ids.put(r.sku(), r.productId());
        }
        assertThat(ids).hasSize(4);
        List<String> oldMain = products.get(ids.get("ATT-1")).images().stream().map(ProductImage::url).toList();
        assertThat(oldMain).hasSize(1).first().asString().contains("utfs.io");

        LIBRARY.addAll(List.of(
                image("ATT-1", "MAIN", "READY", 1600), image("ATT-1", "PT01", "READY", 1200),
                image("ATT-2", "MAIN", "FLAGGED", 1600),
                image("ATT-3", "MAIN", "READY", 800)));

        AttachResult first = library.attach(List.of());
        assertThat(first.productsUpdated()).isEqualTo(2);
        assertThat(first.imagesAttached()).isEqualTo(3);
        assertThat(first.skipped()).isEmpty();
        Product attached = products.get(ids.get("ATT-1"));
        assertThat(attached.images()).extracting(ProductImage::url)
                .containsExactly("https://utfs.io/f/ATT-1.MAIN.jpg", "https://utfs.io/f/ATT-1.PT01.jpg");
        // held listings keep their images; a small main image is attached like any other
        assertThat(products.get(ids.get("ATT-2")).images()).extracting(ProductImage::url)
                .doesNotContain("https://utfs.io/f/ATT-2.MAIN.jpg");
        assertThat(products.get(ids.get("ATT-3")).images()).extracting(ProductImage::url)
                .containsExactly("https://utfs.io/f/ATT-3.MAIN.jpg");
        assertThat(products.get(ids.get("ATT-4")).images()).hasSize(1);

        // running it again changes nothing; naming SKUs limits the run
        assertThat(library.attach(List.of("att-1")).productsUpdated()).isZero();
        List<String> skus = new ArrayList<>(ids.keySet());
        assertThat(library.attach(skus).imagesAttached()).isZero();
        assertThat(library.usage(List.of("https://utfs.io/f/ATT-1.MAIN.jpg"), List.of("att-1", "nope")).knownSkus())
                .containsExactly("ATT-1");
    }
}
