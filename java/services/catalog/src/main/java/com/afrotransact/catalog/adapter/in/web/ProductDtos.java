package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.domain.FlagSource;
import com.afrotransact.catalog.domain.ListingReasonCode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductFlag;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.VariantDraft;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies for /v1/products. */
final class ProductDtos {

    private ProductDtos() {
    }

    @Schema(name = "CreateProductRequest", description = "Exactly one of categoryId or categoryRequestId")
    record CreateProductRequest(
            UUID categoryId,
            UUID categoryRequestId,
            @NotBlank @Size(min = 10, max = 200) String title,
            Map<String, String> attributes,
            @NotEmpty @Size(max = 10) List<@Valid ImageDto> images,
            @NotBlank String variationTheme,
            @NotEmpty @Size(max = 200) List<@Valid VariantRequest> variants,
            @Size(min = 3, max = 3) String currency,
            @Schema(description = "May be sold in a pre-order window; default true for food product types")
            Boolean preorderEligible,
            @Schema(description = "Always ships now by carrier (never a pre-order); default false")
            Boolean shipsNow) {

        CreateProductCommand toCommand() {
            return new CreateProductCommand(categoryId, categoryRequestId, title, attributes,
                    images.stream().map(ImageDto::toDomain).toList(), variationTheme,
                    variants.stream().map(VariantRequest::toDomain).toList(), currency, preorderEligible, shipsNow);
        }
    }

    @Schema(name = "PreorderFlagsRequest", description = "Pre-order flags of a product (S272)")
    record PreorderFlagsRequest(@NotNull Boolean preorderEligible, @NotNull Boolean shipsNow) {
    }

    @Schema(name = "ProductImage")
    record ImageDto(@NotBlank String url, @PositiveOrZero int width, @PositiveOrZero int height,
                    @PositiveOrZero long bytes, @NotBlank String contentType) {

        ProductImage toDomain() {
            return new ProductImage(url, width, height, bytes, contentType);
        }

        static ImageDto of(ProductImage i) {
            return new ImageDto(i.url(), i.width(), i.height(), i.bytes(), i.contentType());
        }
    }

    @Schema(name = "VariantRequest")
    record VariantRequest(@NotNull Map<String, String> options, @NotBlank String sellerSku, String gtin,
                          boolean gtinExempt, @PositiveOrZero long priceMinor, @PositiveOrZero int quantity) {

        VariantDraft toDomain() {
            return new VariantDraft(options, sellerSku, gtin, gtinExempt, priceMinor, quantity);
        }
    }

    @Schema(name = "Variant")
    record VariantResponse(UUID id, String atin, String sellerSku, String gtin, boolean gtinExempt,
                           Map<String, String> options, long priceMinor, String currency, int quantity) {

        static VariantResponse of(Variant v) {
            return new VariantResponse(v.id(), v.atin().value(), v.sellerSku(), v.gtin(), v.gtinExempt(),
                    v.options(), v.priceMinor(), v.currency(), v.quantity());
        }
    }

    @Schema(name = "Product")
    record ProductResponse(UUID id, String atin, String ownerKind, String ownerOrgId, UUID categoryId,
                           UUID categoryRequestId, String productType, String title, Map<String, String> attributes,
                           List<ImageDto> images, String variationTheme, List<VariantResponse> variants, String status,
                           String moderationNote, Instant createdAt, Instant updatedAt, long version,
                           boolean preorderEligible, boolean shipsNow) {

        static ProductResponse of(Product p) {
            return new ProductResponse(p.id(), p.atin().value(), p.owner().kind().name(), p.owner().orgId(),
                    p.categoryId(), p.categoryRequestId(), p.productTypeCode(), p.title(), p.attributes(),
                    p.images().stream().map(ImageDto::of).toList(), p.variationTheme(),
                    p.variants().stream().map(VariantResponse::of).toList(), p.status().name(), p.moderationNote(),
                    p.createdAt(), p.updatedAt(), p.version(), p.preorderEligible(), p.shipsNow());
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
