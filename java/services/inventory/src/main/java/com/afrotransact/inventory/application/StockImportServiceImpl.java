package com.afrotransact.inventory.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.StockCsv;
import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.domain.StockImportMode;
import com.afrotransact.inventory.domain.StockImportRow;
import com.afrotransact.inventory.domain.StockImportStatus;
import com.afrotransact.inventory.domain.StockImportValidator;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.domain.StockRowMessage;
import com.afrotransact.inventory.domain.StockRowOutcome;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.inventory.port.StockImportRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class StockImportServiceImpl implements StockImportService {

    private static final Logger LOG = LoggerFactory.getLogger(StockImportServiceImpl.class);
    /** The largest file (the feed limit of the catalog: 25 MB). */
    static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    private static final int SKU_CHUNK = 1000;
    private static final int STALLED_BATCH = 10;
    private static final Duration STALL_AFTER = Duration.ofMinutes(2);

    private final StockImportRepository imports;
    private final PositionRepository positions;
    private final NodeRepository nodes;
    private final StockImportJob job;
    private final InventoryAccess access;
    private final InventorySettings settings;
    private final Executor executor;
    private final Clock clock;
    private final TransactionTemplate tx;

    StockImportServiceImpl(StockImportRepository imports, PositionRepository positions, NodeRepository nodes,
                           StockImportJob job, InventoryAccess access, InventorySettings settings,
                           @Qualifier("stockImportExecutor") Executor executor, Clock clock,
                           PlatformTransactionManager transactions) {
        this.imports = imports;
        this.positions = positions;
        this.nodes = nodes;
        this.job = job;
        this.access = access;
        this.settings = settings;
        this.executor = executor;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public FileContent template(StockImportMode mode) {
        access.require(InventoryPermissions.INBOUND);
        boolean delta = mode == StockImportMode.DELTA;
        List<List<String>> records = new ArrayList<>();
        records.add(List.of("# One row per lot you want to change. Seller SKU and Location are required."));
        records.add(List.of(delta
                ? "# Change is added to what is on hand now: 5 adds five units, -3 removes three. A file is never applied twice."
                : "# On hand is the number of units you counted. The same file again changes nothing."));
        records.add(List.of("# Lot is needed when the SKU has several lots at the location; Expiry (year-month-day) helps "
                + "to find the lot of perishables."));
        records.add(List.of("# Only your own stock can be changed, never fewer than the units already reserved for orders. "
                + "At most " + StockImportValidator.MAX_STOCK + " units and " + settings.stockImportMaxRows()
                + " rows per file."));
        records.add(List.of("Seller SKU", "Location", delta ? "Change" : "On hand", "Lot", "Expiry"));
        records.add(List.of("MY-SKU-1", "HOU-FC", delta ? "5" : "120", "", ""));
        return new FileContent("afrotransact-stock-" + mode.name().toLowerCase(Locale.ROOT) + ".csv",
                "text/csv; charset=utf-8", StockCsv.write(records));
    }

    @Override
    public StockImport upload(String fileName, byte[] content, StockImportMode mode, String requestedOrg) {
        InventoryAccess.InboundActor who = access.inboundWriter(requestedOrg);
        String org = requireOrg(who.orgId());
        if (content.length == 0) {
            throw new InventoryRuleViolation("inventory.upload-rejected", "The file is empty");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new InventoryRuleViolation("inventory.upload-rejected", "The file is larger than "
                    + MAX_FILE_BYTES / (1024 * 1024) + " MB; split it into several files");
        }
        if (imports.hasActive(org)) {
            throw new ConflictException("inventory.import-already-running", "An earlier stock file is still being "
                    + "imported. Wait for it to finish, then upload this one.");
        }
        StockImportMode wanted = mode == null ? StockImportMode.ABSOLUTE : mode;
        List<List<String>> records = StockCsv.parse(content, settings.stockImportMaxRows() + 1000);
        Set<String> held = heldKeys(org, StockImportValidator.skusOf(records));
        Set<String> activeNodes = nodes.findAll().stream().filter(Node::active).map(Node::id)
                .collect(Collectors.toSet());
        StockImportValidator.Result result = StockImportValidator.validate(records, wanted, activeNodes, held,
                settings.stockImportMaxRows());
        Instant now = Instant.now(clock);
        String sha = sha256(content);
        UUID sameFile = imports.findLatestByHash(org, wanted, sha).map(StockImport::id).orElse(null);
        String name = fileName == null ? "stock.csv" : fileName.replaceAll("[\\\\/]", "_");
        StockImport upload = new StockImport(UUID.randomUUID(), imports.nextReference(), org, who.actor().subject(),
                name.length() > 200 ? name.substring(name.length() - 200) : name, sha, sameFile, wanted, result.header(),
                StockImportStatus.VALIDATED, result.rows().size(), (int) result.count(StockImportRow.VALID),
                (int) result.count(StockImportRow.WARNING), (int) result.count(StockImportRow.ERROR), 0, 0, 0, 0, now,
                null, null, null, 0);
        tx.executeWithoutResult(s -> imports.insert(upload, result.rows()));
        LOG.info("Stock file {}: {} rows ({} valid, {} warnings, {} errors), {} mode", upload.reference(),
                upload.totalRows(), upload.validRows(), upload.warningRows(), upload.errorRows(), wanted);
        return imports.findById(upload.id()).orElseThrow();
    }

    /** {@code SKU|NODE} of every position the organisation holds for these SKUs (chunked: a file may name 50,000). */
    private Set<String> heldKeys(String org, Set<String> skus) {
        Set<String> held = new HashSet<>();
        List<String> all = new ArrayList<>(skus);
        for (int from = 0; from < all.size(); from += SKU_CHUNK) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + SKU_CHUNK));
            for (StockPosition p : positions.findBySkus(chunk, org)) {
                held.add(p.sku() + "|" + p.nodeId());
            }
        }
        return held;
    }

    @Override
    public Page<StockImport> list(String requestedOrg, int page, int size) {
        String org = org(requestedOrg);
        return imports.list(org, Math.max(page, 0), Math.clamp(size, 1, 100));
    }

    @Override
    public StockImport get(UUID id) {
        return owned(id);
    }

    @Override
    public Page<StockImportRow> rows(UUID id, String status, StockRowOutcome outcome, int page, int size) {
        StockImport u = owned(id);
        return imports.rows(u.id(), status, outcome, Math.max(page, 0), Math.clamp(size, 1, 500));
    }

    @Override
    public FileContent report(UUID id) {
        StockImport u = owned(id);
        List<List<String>> records = new ArrayList<>();
        List<String> header = new ArrayList<>(u.header());
        header.addAll(List.of("Row", "Result", "Severity", "Column", "Message", "Before", "After"));
        records.add(header);
        for (StockImportRow r : imports.allRows(u.id())) {
            List<String> line = new ArrayList<>();
            for (int i = 0; i < u.header().size(); i++) {
                line.add(i < r.cells().size() ? r.cells().get(i) : "");
            }
            line.add(Integer.toString(r.rowNumber()));
            line.add(result(r));
            line.add(severity(r));
            line.add(r.messages().stream().map(StockRowMessage::column).filter(c -> c != null).findFirst().map(c -> columnLabel(u, c))
                    .orElse(""));
            line.add(r.messages().isEmpty() ? (r.outcomeNote() == null ? "" : r.outcomeNote()) : r.summary()
                    + (r.outcomeNote() == null ? "" : "; " + r.outcomeNote()));
            line.add(r.before() == null ? "" : r.before().toString());
            line.add(r.after() == null ? "" : r.after().toString());
            records.add(line);
        }
        String stem = u.fileName().contains(".") ? u.fileName().substring(0, u.fileName().lastIndexOf('.')) : u.fileName();
        return new FileContent(stem + "-report.csv", "text/csv; charset=utf-8", StockCsv.write(records));
    }

    private static String columnLabel(StockImport u, String key) {
        return switch (key) {
            case StockImportValidator.SKU -> "Seller SKU";
            case StockImportValidator.LOCATION -> "Location";
            case StockImportValidator.QUANTITY -> u.mode() == StockImportMode.DELTA ? "Change" : "On hand";
            case StockImportValidator.LOT -> "Lot";
            case StockImportValidator.EXPIRY -> "Expiry";
            default -> key;
        };
    }

    private static String result(StockImportRow r) {
        return switch (r.outcome()) {
            case PENDING -> "";
            case APPLIED -> "Applied";
            case UNCHANGED -> "No change";
            case FAILED -> "Failed";
            case SKIPPED -> "Not imported";
        };
    }

    private static String severity(StockImportRow r) {
        if (r.outcome() == StockRowOutcome.FAILED) {
            return "Error";
        }
        if (r.messages().isEmpty()) {
            return "";
        }
        return r.messages().stream().anyMatch(StockRowMessage::isError) ? "Error" : "Warning";
    }

    @Override
    public StockImport start(UUID id, boolean validOnly) {
        InventoryAccess.InboundActor who = access.inboundWriter(null);
        StockImport started = tx.execute(s -> {
            StockImport u = imports.lock(id).filter(x -> InventoryAccess.isStaff(who.actor())
                    || x.ownerOrg().equals(who.orgId())).orElseThrow(() -> notFound(id));
            if (u.status().started()) {
                return u;
            }
            if (u.errorRows() > 0 && !validOnly) {
                throw new ConflictException("inventory.import-has-errors", u.errorRows() + " row(s) have errors: fix "
                        + "them and upload the file again, or import the valid rows only");
            }
            if (u.importableRows() == 0) {
                throw new ConflictException("inventory.import-nothing-valid", "Every row has errors; nothing can be "
                        + "imported");
            }
            if (u.mode() == StockImportMode.DELTA) {
                imports.findStartedByHash(u.ownerOrg(), u.mode(), u.fileSha256(), u.id()).ifPresent(x -> {
                            throw new ConflictException("inventory.import-already-applied", "You already applied this "
                                    + "file on " + x.startedAt() + " (" + x.reference() + "). A change is never applied "
                                    + "twice.");
                        });
            }
            if (imports.hasActive(u.ownerOrg())) {
                throw new ConflictException("inventory.import-already-running", "An earlier stock file is still being "
                        + "imported. Wait for it to finish.");
            }
            if (validOnly) {
                imports.skipErrorRows(u.id());
            }
            return imports.update(u.start(validOnly, Instant.now(clock)));
        });
        if (started != null && started.status().active()) {
            executor.execute(() -> job.drive(started.id()));
        }
        return started;
    }

    @Override
    public int resumeStalled() {
        List<UUID> stalled = imports.stalled(Instant.now(clock).minus(STALL_AFTER), STALLED_BATCH);
        for (UUID id : stalled) {
            LOG.info("Resuming stalled stock import {}", id);
            job.drive(id);
        }
        return stalled.size();
    }

    // ------------------------------------------------------------------ access

    /** The organisation the caller acts for: a seller its own; staff the one named. */
    private String org(String requested) {
        Actor actor = access.require(InventoryPermissions.READ);
        if (InventoryAccess.isStaff(actor)) {
            return requireOrg(requested == null || requested.isBlank() ? null : requested.trim());
        }
        return requireOrg(actor.orgId().orElse(null));
    }

    private String requireOrg(String org) {
        if (org == null || org.isBlank()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Name the seller organisation");
        }
        if (org.equals(settings.retailOrgId())) {
            throw new ForbiddenActionException("inventory.retail-stock", "AfroTransact's own stock cannot be changed "
                    + "with a file");
        }
        return org;
    }

    private StockImport owned(UUID id) {
        Actor actor = access.require(InventoryPermissions.READ);
        StockImport u = imports.findById(id).orElseThrow(() -> notFound(id));
        if (!InventoryAccess.isStaff(actor) && !actor.orgId().map(u.ownerOrg()::equals).orElse(false)) {
            throw notFound(id);
        }
        return u;
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("inventory.import-not-found", "Stock file " + id + " not found");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
