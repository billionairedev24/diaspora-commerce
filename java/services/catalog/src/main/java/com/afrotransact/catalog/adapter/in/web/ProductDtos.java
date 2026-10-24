package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.UpdateProductCommand;
import com.afrotransact.catalog.domain.FlagSource;
import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ListingReasonCode;
import com.afrotransact.catalog.domain.PackageDimensions;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductFlag;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ShipFromRef;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.VariantDraft;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies for /v1/products. */
final class ProductDtos {

    private ProductDtos() {
    }

    @Schema(name = "CreateProductRequest", description = "Exactly one of categoryId or categoryRequestId. "
            + "variationTheme empty (or absent) = a product without variations: exactly one variant without options")
    record CreateProductRequest(
            UUID categoryId,
            UUID categoryRequestId,
            @NotBlank @Size(min = 10, max = 200) String title,
            Map<String, String> attributes,
            @NotEmpty @Size(max = 10) List<@Valid ImageDto> images,
            @Size(max = 60) String variationTheme,
            @NotEmpty @Size(max = 200) List<@Valid VariantRequest> variants,
            @Size(min = 3, max = 3) String currency,
            @Schema(description = "May be sold in a pre-order window; default true for food product types")
            Boolean preorderEligible,
            @Schema(description = "Always ships now by carrier (never a pre-order); default false")
            Boolean shipsNow,
            @Valid ListingDto listing,
            @Schema(description = "The saved draft this submission comes from; deleted with the submission")
            UUID draftId) {

        CreateProductCommand toCommand() {
            return new CreateProductCommand(categoryId, categoryRequestId, title, attributes,
                    images.stream().map(ImageDto::toDomain).toList(), variationTheme,
                    variants.stream().map(VariantRequest::toDomain).toList(), currency, preorderEligible, shipsNow,
                    listing == null ? null : listing.toDomain(), draftId);
        }
    }

    @Schema(name = "UpdateProductRequest", description = "The whole editable content of a product (one-step form in "
            + "edit mode). Variants are matched by sellerSku; a published listing keeps its category and variants")
    record UpdateProductRequest(
            UUID categoryId,
            @NotBlank @Size(min = 10, max = 200) String title,
            Map<String, String> attributes,
            @NotEmpty @Size(max = 10) List<@Valid ImageDto> images,
            @Size(max = 60) String variationTheme,
            @NotEmpty @Size(max = 200) List<@Valid VariantRequest> variants,
            Boolean preorderEligible,
            Boolean shipsNow,
            @Valid ListingDto listing,
            @Schema(description = "The version the edit is based on (409 when the product changed since)")
            Long version,
            UUID draftId) {

        UpdateProductCommand toCommand() {
            return new UpdateProductCommand(categoryId, title, attributes,
                    images.stream().map(ImageDto::toDomain).toList(), variationTheme,
                    variants.stream().map(VariantRequest::toDomain).toList(), preorderEligible, shipsNow,
                    listing == null ? null : listing.toDomain(), version, draftId);
        }
    }

    @Schema(name = "PackageDimensions", description = "Packed size (inches) and weight (pounds)")
    record PackageDto(@Positive double lengthIn, @Positive double widthIn, @Positive double heightIn,
                      @Positive double weightLb) {

        PackageDimensions toDomain() {
            return new PackageDimensions(lengthIn, widthIn, heightIn, weightLb);
        }

        static PackageDto of(PackageDimensions p) {
            return p == null ? null : new PackageDto(p.lengthIn(), p.widthIn(), p.heightIn(), p.weightLb());
        }
    }

    @Schema(name = "ShipFrom", description = "The seller's ship-from location (snapshot)")
    record ShipFromDto(@Size(max = 80) String locationId, @Size(max = 80) String label, @Size(max = 80) String city,
                       @Size(max = 80) String region, @Size(max = 2) String country,
                       @Size(max = 12) String postalCode) {

        ShipFromRef toDomain() {
            return new ShipFromRef(locationId, label, city, region, country, postalCode);
        }

        static ShipFromDto of(ShipFromRef s) {
            return s == null ? null : new ShipFromDto(s.locationId(), s.label(), s.city(), s.region(), s.country(),
                    s.postalCode());
        }
    }

    @Schema(name = "ListingDetails", description = "Description, condition, search keywords, fulfilment, package, "
            + "handling time and ship-from")
    record ListingDto(@Size(max = 5000) String description, ItemCondition condition,
                      @Size(max = 20) List<@Size(max = 40) String> keywords, FulfilmentMode fulfilment,
                      @Valid PackageDto packaging,
                      @Schema(description = "Business days from order to dispatch, 1–30 (default 2)")
                      Integer handlingDays,
                      @Valid ShipFromDto shipFrom) {

        ListingDetails toDomain() {
            return new ListingDetails(description, condition, keywords, fulfilment,
                    packaging == null ? null : packaging.toDomain(), handlingDays == null ? 0 : handlingDays,
                    shipFrom == null ? null : shipFrom.toDomain());
        }

        static ListingDto of(ListingDetails d) {
            return new ListingDto(d.description(), d.condition(), d.keywords(), d.fulfilment(),
                    PackageDto.of(d.packaging()), d.handlingDays(), ShipFromDto.of(d.shipFrom()));
        }
    }

    @Schema(name = "PreorderFlagsRequest", description = "Pre-order flags of a product (S272)")
    record PreorderFlagsRequest(@NotNull Boolean preorderEligible, @NotNull Boolean shipsNow) {
    }

    @Schema(name = "ProductImage")
    record ImageDto(@NotBlank String url, @PositiveOrZero int width, @PositiveOrZero int height,
                    @PositiveOrZero long bytes, @NotBlank String contentType,
                    @Schema(description = "Optional alternative text (what the picture shows), stored with the image")
                    @Size(max = 200) String alt) {

        ProductImage toDomain() {
            return new ProductImage(url, width, height, bytes, contentType, alt == null || alt.isBlank() ? null : alt.trim());
        }

        static ImageDto of(ProductImage i) {
            return new ImageDto(i.url(), i.width(), i.height(), i.bytes(), i.contentType(), i.alt());
        }
    }

    @Schema(name = "VariantRequest")
    record VariantRequest(@NotNull Map<String, String> options, @NotBlank String sellerSku, String gtin,
                          boolean gtinExempt, @PositiveOrZero long priceMinor, @PositiveOrZero int quantity,
                          @Schema(description = "\"Was\" price shown struck through; above priceMinor")
                          @Positive Long compareAtMinor,
                          @Schema(description = "Images of this variant only (e.g. its colour)")
                          @Size(max = 5) List<@Valid ImageDto> images) {

        VariantDraft toDomain() {
            return new VariantDraft(options, sellerSku, gtin, gtinExempt, priceMinor, quantity, compareAtMinor,
                    images == null ? List.of() : images.stream().map(ImageDto::toDomain).toList());
        }
    }

    @Schema(name = "Variant")
    record VariantResponse(UUID id, String atin, String sellerSku, String gtin, boolean gtinExempt,
                           Map<String, String> options, long priceMinor, String currency, int quantity,
                           Long compareAtMinor, List<ImageDto> images) {

        static VariantResponse of(Variant v) {
            return new VariantResponse(v.id(), v.atin().value(), v.sellerSku(), v.gtin(), v.gtinExempt(),
                    v.options(), v.priceMinor(), v.currency(), v.quantity(), v.compareAtMinor(),
                    v.images().stream().map(ImageDto::of).toList());
        }
    }

    @Schema(name = "Product")
    record ProductResponse(UUID id, String atin, String ownerKind, String ownerOrgId, UUID categoryId,
                           UUID categoryRequestId, String productType, String title, Map<String, String> attributes,
                           List<ImageDto> images, String variationTheme, List<VariantResponse> variants, String status,
                           String moderationNote, Instant createdAt, Instant updatedAt, long version,
                           boolean preorderEligible, boolean shipsNow, ListingDto listing) {

        static ProductResponse of(Product p) {
            return new ProductResponse(p.id(), p.atin().value(), p.owner().kind().name(), p.owner().orgId(),
                    p.categoryId(), p.categoryRequestId(), p.productTypeCode(), p.title(), p.attributes(),
                    p.images().stream().map(ImageDto::of).toList(), p.variationTheme(),
                    p.variants().stream().map(VariantResponse::of).toList(), p.status().name(), p.moderationNote(),
                    p.createdAt(), p.updatedAt(), p.version(), p.preorderEligible(), p.shipsNow(),
                    ListingDto.of(p.details()));
        }
    }

    @Schema(name = "ProductPage")
    record ProductPageResponse(List<ProductResponse> items, long total, int page, int size) {
    }

    @Schema(name = "ModerationNote")
    record NoteRequest(@NotBlank @Size(max = 1000) String note) {
    }

    @Schema(name = "FlagListingRequest", description = "Reason code required; details required for OTHER")
    record FlagRequest(@NotNull ListingReasonCode reasonCode, @Size(max = 1000) String details,
                       @Size(max = 20) String riskLevel) {
    }

    @Schema(name = "ReportListingRequest", description = "Reason code required; details required for OTHER")
    record ReportRequest(@NotNull ListingReasonCode reasonCode, @Size(max = 1000) String details) {
    }

    @Schema(name = "ListingFlag", description = "A report or flag on a live listing (catalog.product.flagged)")
    record FlagResponse(UUID id, UUID productId, FlagSource source, ListingReasonCode reasonCode, String details,
                        String riskLevel, Instant createdAt, boolean created) {

        static FlagResponse of(ProductFlag f, boolean created) {
            return new FlagResponse(f.id(), f.productId(), f.source(), f.reasonCode(), f.details(), f.riskLevel(),
                    f.createdAt(), created);
        }
    }

    @Schema(name = "TakedownRequest", description = "Reason code required; note required for OTHER")
    record TakedownRequest(@NotNull ListingReasonCode reasonCode, @Size(max = 1000) String note) {
    }

    @Schema(name = "RestoreListingRequest")
    record RestoreRequest(@Size(max = 1000) String note) {
    }
}
