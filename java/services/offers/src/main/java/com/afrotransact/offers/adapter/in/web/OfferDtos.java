package com.afrotransact.offers.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.application.CreateOfferCommand;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.application.PageView;
import com.afrotransact.offers.application.StockUpdate;
import com.afrotransact.offers.application.UpdateOfferCommand;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.domain.StockStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request / response bodies of the seller-facing offer API. */
final class OfferDtos {

    private OfferDtos() {
    }

    @Schema(name = "ShipFrom", description = "Where a seller-fulfilled order ships from. AFROTRANSACT_DELIVERY "
            + "offers give only locationId: one of the seller's ship-from locations with AfroTransact pickup enabled "
            + "(seller-service); city, region, country and ZIP are copied from it")
    record ShipFromDto(@Size(max = 80) @Schema(example = "Houston") String city,
                       @Size(max = 80) @Schema(example = "TX") String region,
                       @Pattern(regexp = "[A-Z]{2}") @Schema(example = "US") String country,
                       @Size(max = 12) @Schema(example = "77002") String postalCode,
                       @Size(max = 64) @Schema(description = "seller-service ship-from location id (required for "
                               + "AFROTRANSACT_DELIVERY)") String locationId) {

        ShipFrom toDomain() {
            return new ShipFrom(city, region, country, postalCode, locationId);
        }

        static ShipFromDto of(ShipFrom s) {
            return s == null ? null
                    : new ShipFromDto(s.city(), s.region(), s.country(), s.postalCode(), s.locationId());
        }
    }

    @Schema(name = "CreateOfferRequest", description = "Exactly one of atin (variant ATIN) or gtin")
    record CreateOfferRequest(
            @Pattern(regexp = "AT[0-9A-Z]{8}") @Schema(example = "AT0000ABCD") String atin,
            @Pattern(regexp = "[0-9 -]{8,17}") @Schema(example = "4006381333931") String gtin,
            @Schema(description = "Staff / service only; sellers always offer for their own organisation")
            String sellerOrgId,
            @NotBlank @Size(max = 40) @Schema(example = "JOL-8") String sellerSku,
            @Size(max = 40) @Schema(description = "inventory-service SKU for Fulfilled by AfroTransact stock "
                    + "(defaults to sellerSku)") String inventorySku,
            @Schema(defaultValue = "NEW") OfferCondition condition,
            @Positive @Schema(example = "899") long priceMinor,
            @Positive @Schema(description = "Strike-through list price, ≥ price") Long listPriceMinor,
            @Pattern(regexp = "[A-Za-z]{3}") @Schema(example = "USD", defaultValue = "USD") String currency,
            @PositiveOrZero @Schema(description = "Shipping charge added to the price (landed price)")
            long shippingMinor,
            @NotNull Fulfilment fulfilment,
            @PositiveOrZero @Schema(description = "Seller-declared stock (required for SELLER fulfilment)")
            Integer quantity,
            @Min(0) @Max(30) @Schema(description = "Days to hand the order to the carrier", defaultValue = "2")
            Integer handlingDays,
            @Valid ShipFromDto shipFrom) {

        CreateOfferCommand toCommand() {
            return new CreateOfferCommand(atin, gtin, sellerOrgId, sellerSku, inventorySku, condition, priceMinor,
                    listPriceMinor, currency, shippingMinor, fulfilment, quantity, handlingDays,
                    shipFrom == null ? null : shipFrom.toDomain());
        }
    }

    @Schema(name = "UpdateOfferRequest", description = "Only the fields present change; listPriceMinor 0 clears it")
    record UpdateOfferRequest(OfferCondition condition, @Positive Long priceMinor,
                              @PositiveOrZero Long listPriceMinor, @PositiveOrZero Long shippingMinor,
                              Fulfilment fulfilment, @PositiveOrZero Integer quantity,
                              @Min(0) @Max(30) Integer handlingDays, @Valid ShipFromDto shipFrom,
                              @Size(max = 40) String inventorySku,
                              @Schema(description = "Version you edited (alternative to If-Match); a newer stored "
                                      + "version answers 409 offers.version-mismatch")
                              @PositiveOrZero Long version) {

        UpdateOfferCommand toCommand() {
            return new UpdateOfferCommand(condition, priceMinor, listPriceMinor, shippingMinor, fulfilment, quantity,
                    handlingDays, shipFrom == null ? null : shipFrom.toDomain(), inventorySku);
        }
    }

    @Schema(name = "Offer", description = "A seller's offer as seen in seller center")
    record OfferResponse(UUID id, String atin, String productAtin, UUID productId, String gtin, UUID categoryId,
                         String sellerOrgId, String sellerName, SellerKind sellerKind, String sellerSku,
                         String inventorySku, OfferCondition condition, long priceMinor, Long listPriceMinor,
                         long shippingMinor, long landedPriceMinor, String currency, Fulfilment fulfilment,
                         @Schema(description = "Seller-declared quantity (SELLER fulfilment)") int quantity,
                         @Schema(description = "Sellable units now") int availableQty, StockStatus stockStatus,
                         int handlingDays, int deliveryDays, ShipFromDto shipFrom, String shipsFrom,
                         OfferStatus status, boolean productLive,
                         @Schema(description = "Hidden by seller enforcement or restriction") boolean suppressed,
                         Instant createdAt, Instant updatedAt, long version) {

        static OfferResponse of(OfferView v) {
            Offer o = v.offer();
            return new OfferResponse(o.id(), o.atin(), o.productAtin(), o.productId(), o.gtin(), o.categoryId(),
                    o.sellerOrgId(), v.sellerName(), o.sellerKind(), o.sellerSku(), o.inventorySku(), o.condition(),
                    o.priceMinor(), o.listPriceMinor(), o.shippingMinor(), o.landedPriceMinor(), o.currency(),
                    o.fulfilment(), o.sellerQuantity(), v.availableQty(), v.stockStatus(), o.handlingDays(),
                    v.deliveryDays(), ShipFromDto.of(o.shipFrom()), shipsFromLabel(o), o.status(), o.productLive(),
                    v.suppressed(), o.createdAt(), o.updatedAt(), o.version());
        }
    }

    @Schema(name = "OfferPage")
    record OfferPageResponse(List<OfferResponse> items, long total, int page, int size) {

        static OfferPageResponse of(PageView<OfferView> p) {
            return new OfferPageResponse(p.items().stream().map(OfferResponse::of).toList(), p.total(), p.page(),
                    p.size());
        }
    }

    /** "AfroTransact" for Fulfilled by AfroTransact, else the ship-from city ("Houston, TX"). */
    static String shipsFromLabel(Offer o) {
        if (o.fulfilment() == Fulfilment.AFROTRANSACT) {
            return "AfroTransact";
        }
        return o.shipFrom() == null ? null : o.shipFrom().label();
    }

    @Schema(name = "StockUpdateRequest", description = "Absolute quantity on hand for a seller-fulfilled SKU")
    record StockUpdateRequest(@NotNull @Min(0) @Max(99_999) @Schema(example = "24") Integer quantity,
                              @Size(max = 40) @Schema(example = "STOCK_TAKE",
                                      description = "STOCK_TAKE, DAMAGED, FOUND, RETURNED or OTHER") String reason) {
    }

    @Schema(name = "StockUpdate", description = "The quantity now declared on the SKU's offers")
    record StockUpdateResponse(String sku, int quantity, String reason, List<UUID> offerIds) {

        static StockUpdateResponse of(StockUpdate u) {
            return new StockUpdateResponse(u.sku(), u.quantity(), u.reason(), u.offerIds());
        }
    }
}
