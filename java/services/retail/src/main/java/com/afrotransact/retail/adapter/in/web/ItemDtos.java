package com.afrotransact.retail.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.application.ItemCommand;
import com.afrotransact.retail.domain.Gtin;
import com.afrotransact.retail.domain.ListingStatus;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.StorageType;
import com.afrotransact.retail.domain.SupplierItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies for the 1P product master. */
final class ItemDtos {

    private ItemDtos() {
    }

    @Schema(name = "SupplierItem", description = "How one supplier sells the item (cost in the supplier's currency)")
    record SupplierItemDto(@NotNull UUID supplierId, @NotBlank @Size(max = 60) String supplierSku,
                           @Positive int casePack, @PositiveOrZero Integer minOrderQty,
                           @PositiveOrZero long unitCostMinor, @NotBlank @Size(min = 3, max = 3) String currency) {

        SupplierItem toDomain() {
            return new SupplierItem(supplierId, supplierSku, casePack, minOrderQty == null ? 0 : minOrderQty,
                    unitCostMinor, currency);
        }

        static SupplierItemDto of(SupplierItem s) {
            return new SupplierItemDto(s.supplierId(), s.supplierSku(), s.casePack(), s.minOrderQty(),
                    s.unitCostMinor(), s.currency());
        }
    }

    @Schema(name = "ItemRequest", description = "Product master; SKU and GTIN are issued automatically, listing starts "
            + "as DRAFT. atin is optional on create and ignored on update (use PUT /atin).")
    record ItemRequest(@NotBlank @Size(min = 3, max = 200) String name, @Size(max = 120) String category,
                       @Pattern(regexp = "AT[0-9A-Z]{8}") String atin, @NotNull UUID primarySupplierId,
                       @NotEmpty @Size(max = 20) List<@Valid SupplierItemDto> supplierItems,
                       @Pattern(regexp = "[A-Z]{2}") String originCountry,
                       @Schema(example = "0910.91.0000") @NotBlank String hsCode,
                       @DecimalMin("0") @DecimalMax("100") BigDecimal dutyRatePercent,
                       @Positive int shelfLifeDays, @NotNull StorageType storage, @PositiveOrZero Integer reorderPoint,
                       @Schema(example = "6 oz jar") @Size(max = 60) String packSize,
                       @Schema(description = "Units per inner pack (default 1)") @Positive Integer innerPack,
                       @Positive int casePack,
                       @Schema(description = "Unit gross weight for weight-based freight allocation")
                       @PositiveOrZero Integer unitWeightGrams,
                       @Schema(description = "Standard landed cost per unit, base currency minor units")
                       @PositiveOrZero long standardCostMinor,
                       @Schema(description = "Selling price, base currency minor units") @PositiveOrZero long priceMinor) {

        ItemCommand toCommand() {
            return new ItemCommand(name, category, atin, primarySupplierId,
                    supplierItems.stream().map(SupplierItemDto::toDomain).toList(), originCountry, hsCode,
                    dutyRatePercent, shelfLifeDays, storage, reorderPoint == null ? 0 : reorderPoint, packSize,
                    innerPack == null ? 1 : innerPack, casePack, unitWeightGrams == null ? 0 : unitWeightGrams,
                    standardCostMinor, priceMinor);
        }
    }

    @Schema(name = "LinkAtinRequest")
    record LinkAtinRequest(@NotBlank @Pattern(regexp = "AT[0-9A-Z]{8}") String atin) {
    }

    @Schema(name = "ListingRequest")
    record ListingRequest(@NotNull ListingStatus status) {
    }

    @Schema(name = "Item")
    record ItemResponse(UUID id, String sku, String gtin, String gtin14, String name, String category, String atin,
                        UUID primarySupplierId, List<SupplierItemDto> supplierItems, String originCountry,
                        String hsCode, BigDecimal dutyRatePercent, int shelfLifeDays, String storage,
                        int reorderPoint, String packSize, int innerPack, int casePack, int unitWeightGrams,
                        long standardCostMinor, long priceMinor, String currency, String listingStatus,
                        Instant createdAt, Instant updatedAt, long version) {

        static ItemResponse of(RetailItem i) {
            return new ItemResponse(i.id(), i.sku(), i.gtin(), Gtin.toGtin14(i.gtin()), i.name(), i.category(),
                    i.atin(), i.primarySupplierId(), i.supplierItems().stream().map(SupplierItemDto::of).toList(),
                    i.originCountry(), i.hsCode(), i.dutyRatePercent(), i.shelfLifeDays(), i.storage().name(),
                    i.reorderPoint(), i.packSize(), i.innerPack(), i.casePack(), i.unitWeightGrams(),
                    i.standardCostMinor(), i.priceMinor(), i.currency(), i.listingStatus().name(), i.createdAt(),
                    i.updatedAt(), i.version());
        }
    }
}
