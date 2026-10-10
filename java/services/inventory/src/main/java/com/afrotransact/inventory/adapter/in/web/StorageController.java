package com.afrotransact.inventory.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.inventory.application.StorageService;
import com.afrotransact.inventory.port.StorageRepository.StorageDay;
import com.afrotransact.inventory.port.StorageRepository.StoredDimensions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Storage", description = "FBA storage volume for metered billing (S206)")
@RestController
class StorageController {

    private final StorageService storage;

    StorageController(StorageService storage) {
        this.storage = storage;
    }

    @Schema(name = "SkuDimensionsRequest", description = "Outer dimensions of one sellable unit, in inches")
    record DimensionsRequest(@NotNull @Schema(examples = "12") BigDecimal lengthIn,
                             @NotNull @Schema(examples = "8") BigDecimal widthIn,
                             @NotNull @Schema(examples = "4.5") BigDecimal heightIn) {
    }

    @Schema(name = "SkuDimensions")
    record DimensionsResponse(String sku, BigDecimal lengthIn, BigDecimal widthIn, BigDecimal heightIn,
                              @Schema(description = "Volume of one unit in cubic feet") BigDecimal unitCubicFeet,
                              String updatedBy, Instant updatedAt) {

        static DimensionsResponse of(StoredDimensions s) {
            return new DimensionsResponse(s.dimensions().sku(), s.dimensions().lengthIn(), s.dimensions().widthIn(),
                    s.dimensions().heightIn(), s.unitCubicFeet(), s.updatedBy(), s.updatedAt());
        }
    }

    @Schema(name = "StorageSnapshotResult")
    record SnapshotResponse(@Schema(description = "UTC day the snapshot is for") LocalDate day,
                            @Schema(description = "Owner × node rows written") int rows) {
    }

    @Schema(name = "StorageDay", description = "Storage of one owner organisation on one UTC day (all fulfilment centres)")
    record StorageDayResponse(String ownerOrg, LocalDate day, long units,
                              @Schema(description = "Units billed at the default unit volume (SKU has no dimensions)")
                              long unmeasuredUnits,
                              @Schema(description = "Cubic feet held that day = the day's cubic-feet-days")
                              BigDecimal cubicFeet) {

        static StorageDayResponse of(StorageDay d) {
            return new StorageDayResponse(d.ownerOrg(), d.day(), d.units(), d.unmeasuredUnits(), d.cubicFeet());
        }
    }

    @Schema(name = "StorageUsage")
    record UsageResponse(LocalDate from, LocalDate to, List<StorageDayResponse> items) {
    }

    @Operation(summary = "Set a SKU's unit dimensions (inventory:adjust or inventory:receive)")
    @PutMapping("/v1/skus/{sku}/dimensions")
    DimensionsResponse setDimensions(@PathVariable String sku, @Valid @RequestBody DimensionsRequest body) {
        return DimensionsResponse.of(storage.setDimensions(sku, body.lengthIn(), body.widthIn(), body.heightIn()));
    }

    @Operation(summary = "A SKU's unit dimensions (inventory:read)")
    @GetMapping("/v1/skus/{sku}/dimensions")
    DimensionsResponse dimensions(@PathVariable String sku) {
        return DimensionsResponse.of(storage.dimensions(sku));
    }

    @Operation(summary = "Retake today's storage snapshot now (inventory:adjust)",
            description = "The hourly job does this; the last snapshot of a UTC day stands. Idempotent.")
    @PostMapping("/v1/storage/snapshots")
    SnapshotResponse snapshot() {
        StorageService.SnapshotResult r = storage.snapshotToday();
        return new SnapshotResponse(r.day(), r.rows());
    }

    @Operation(summary = "Cubic-feet-days per owner organisation and UTC day (metered billing, S206)",
            description = "SERVICE principals with inventory:storage.read (seller-service) and staff with "
                    + "inventory:read see every owner (optionally ownerOrg); seller members only their own "
                    + "organisation. Inclusive range of at most 93 days; physical stock in fulfilment centres only "
                    + "(on hand + damaged).")
    @GetMapping("/v1/storage/usage")
    ResponseEntity<UsageResponse> usage(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) String ownerOrg) {
        UsageResponse body = new UsageResponse(from, to, storage.usage(from, to, ownerOrg).stream()
                .map(StorageDayResponse::of).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
