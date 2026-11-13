package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.UpdateProductCommand;
import com.afrotransact.catalog.domain.FlagSource;
import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ListingDocument;
import com.afrotransact.catalog.domain.ListingReasonCode;
import com.afrotransact.catalog.domain.PackageDimensions;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductAction;
import com.afrotransact.catalog.domain.ProductFlag;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ShipFromRef;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.tax.MissingTaxFact;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
            + "edit mode). Variants are matched by sellerSku, else GTIN, else ATIN; a published listing keeps its category and variants")
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
                      @Valid ShipFromDto shipFrom,
                      @Schema(description = "The seller's size chart for this product (POST /v1/size-charts). Required "
                              + "when the product type's sizing trait says sizeChartRequired; every size the variants "
                              + "sell needs a row in it. Must be a chart of the same organisation.")
                      UUID sizeChartId,
                      @Schema(description = "How the sizes fit; shown beside the size chart (defaults to the "
                              + "chart's own fit note)")
                      FitNote fitNote,
                      @Schema(description = "How this seller sells the item, answering the category's behaviour "
                              + "questions (sold_heated, heated_by_seller, utensils_provided): fact id to answer. "
                              + "Part of \"Your listing\"; never a tax code.")
                      Map<String, Object> sellerBehaviour,
                      @Schema(description = "The dangerous goods answer (aerosol, flammable, battery, candle, cleaner). "
                              + "Required where the category's rules ask the question, ignored elsewhere. true marks "
                              + "the listing: fulfilment by AfroTransact and carrier-gated flows read it.")
                      Boolean dangerousGoods,
                      @Schema(description = "Documents uploaded on the form (permit, brand authorization, safety "
                              + "certificate, insurance, invoice). Kept and marked PENDING_REVIEW for the approval "
                              + "engine; they never block the listing here.")
                      @Size(max = 20) List<@Valid DocumentDto> documents,
                      @Schema(description = "Rule field id to the reason the seller cannot provide a field the rules "
                              + "mark required_or_exemption (material, model_number, age_grade, shelf_life, "
                              + "label_photo ...)")
                      Map<String, @Size(max = 300) String> exemptions) {

        ListingDetails toDomain() {
            return new ListingDetails(description, condition, keywords, fulfilment,
                    packaging == null ? null : packaging.toDomain(), handlingDays == null ? 0 : handlingDays,
                    shipFrom == null ? null : shipFrom.toDomain(), sizeChartId, fitNote, sellerBehaviour,
                    dangerousGoods, documents == null ? null : documents.stream().map(DocumentDto::toDomain).toList(),
                    exemptions);
        }

        static ListingDto of(ListingDetails d) {
            return new ListingDto(d.description(), d.condition(), d.keywords(), d.fulfilment(),
                    PackageDto.of(d.packaging()), d.handlingDays(), ShipFromDto.of(d.shipFrom()), d.sizeChartId(),
                    d.fitNote(), d.sellerBehaviour(), d.dangerousGoods(),
                    d.documents().stream().map(DocumentDto::of).toList(), d.exemptions());
        }
    }

    @Schema(name = "ListingDocument", description = "A document uploaded on the product form, reviewed by the approval engine")
    record DocumentDto(@NotBlank @Size(max = 40) String kind, @NotBlank String url, @Size(max = 200) String fileName,
                       @NotBlank String contentType, @PositiveOrZero long bytes, LocalDate expiresOn,
                       @Schema(description = "PENDING_REVIEW until the approval engine decides; set by the server")
                       String status) {

        ListingDocument toDomain() {
            return new ListingDocument(kind, url, fileName, contentType, bytes, expiresOn);
        }

        static DocumentDto of(ListingDocument d) {
            return new DocumentDto(d.kind(), d.url(), d.fileName(), d.contentType(), d.bytes(), d.expiresOn(), d.status());
        }
    }

    @Schema(name = "PreorderFlagsRequest", description = "Pre-order flags of a product (S272)")
    record PreorderFlagsRequest(@NotNull Boolean preorderEligible, @NotNull Boolean shipsNow) {
    }

    @Schema(name = "ProductImage", description = "A picture of the listing: by its public URL, or by media asset id for a "
            + "photo that is held for review (it has no public URL yet)")
    record ImageDto(@Schema(description = "Public URL. Empty while the photo is held for review (then assetId is set)")
                    String url, @PositiveOrZero int width, @PositiveOrZero int height,
                    @PositiveOrZero long bytes, String contentType,
                    @Schema(description = "Optional alternative text (what the picture shows), stored with the image")
                    @Size(max = 200) String alt,
                    @Schema(description = "LABEL for the label photo (ingredients and nutrition panel, Supplement "
                            + "Facts, Drug Facts); absent for a product picture")
                    @Size(max = 20) String role,
                    @Schema(description = "Media asset id of a photo uploaded to media-service. Send it instead of url for a "
                            + "photo that is held for review: catalog asks media (the asset must belong to the seller), "
                            + "publishes it into the listing when it is cleared, and keeps the listing off sale while it "
                            + "is held. Never present once the photo is published.")
                    @Size(max = 64) String assetId,
                    @Schema(description = "Response only: true while the photo is held for review (We are checking your "
                            + "photo); the listing is saved but not live until it is cleared", accessMode = Schema.AccessMode.READ_ONLY)
                    Boolean held) {

        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "Give the picture's url or its asset id")
        boolean isReferenced() {
            return url != null && !url.isBlank() || assetId != null && !assetId.isBlank();
        }

        ProductImage toDomain() {
            String alternative = alt == null || alt.isBlank() ? null : alt.trim();
            if (url == null || url.isBlank()) {
                return ProductImage.held(assetId, alternative, role);
            }
            return new ProductImage(url, width, height, bytes, contentType == null ? "" : contentType, alternative, role);
        }

        static ImageDto of(ProductImage i) {
            return new ImageDto(i.url(), i.width(), i.height(), i.bytes(), i.contentType(), i.alt(), i.role(), i.assetId(),
                    i.isHeld() ? Boolean.TRUE : null);
        }
    }

    @Schema(name = "VariantRequest")
    record VariantRequest(@NotNull Map<String, String> options,
                          @Schema(description = "Optional: when empty a stable, organisation-prefixed SKU is generated")
                          String sellerSku, String gtin,
                          boolean gtinExempt, @PositiveOrZero long priceMinor, @PositiveOrZero int quantity,
                          @Schema(description = "\"Was\" price shown struck through; above priceMinor")
                          @Positive Long compareAtMinor,
                          @Schema(description = "Images of this variant only (e.g. its colour)")
                          @Size(max = 5) List<@Valid ImageDto> images,
                          @Schema(description = "Existing variant ATIN: matches the variant when sellerSku and gtin "
                                  + "are absent (edit and bulk update)")
                          String atin,
                          @Schema(description = "Product facts of this variant by fact id (net_weight_oz, "
                                  + "servings_per_package, ...): the category's tax questions in plain words")
                          Map<String, Object> taxFacts,
                          @Schema(description = "The package of this variant as shipped (inches, pounds). Required for "
                                  + "every physical variant; when absent the listing's package is the default")
                          @Valid PackageDto packaging) {

        VariantDraft toDomain() {
            return new VariantDraft(options, sellerSku, gtin, gtinExempt, priceMinor, quantity, compareAtMinor,
                    images == null ? List.of() : images.stream().map(ImageDto::toDomain).toList(), atin, taxFacts,
                    packaging == null ? null : packaging.toDomain());
        }
    }

    @Schema(name = "Variant")
    record VariantResponse(UUID id, String atin, String sellerSku, String gtin, boolean gtinExempt,
                           Map<String, String> options, long priceMinor, String currency, int quantity,
                           Long compareAtMinor, List<ImageDto> images, boolean sellerSkuGenerated,
                           Map<String, Object> taxFacts, String taxStatus, List<MissingTaxFact> missingTaxFacts,
                           PackageDto packaging) {

        static VariantResponse of(Variant v) {
            return new VariantResponse(v.id(), v.atin().value(), v.sellerSku(), v.gtin(), v.gtinExempt(),
                    v.options(), v.priceMinor(), v.currency(), v.quantity(), v.compareAtMinor(),
                    v.images().stream().map(ImageDto::of).toList(), v.sellerSkuGenerated(), v.tax().facts(),
                    v.tax().status().name(), v.tax().missing(), PackageDto.of(v.packaging()));
        }
    }

    @Schema(name = "Product")
    record ProductResponse(UUID id, String atin, String ownerKind, String ownerOrgId, UUID categoryId,
                           UUID categoryRequestId, String productType, String title, Map<String, String> attributes,
                           List<ImageDto> images, String variationTheme, List<VariantResponse> variants, String status,
                           String moderationNote, Instant createdAt, Instant updatedAt, long version,
                           boolean preorderEligible, boolean shipsNow, ListingDto listing,
                           @Schema(description = "What the catalog's state machine allows on this page now, whatever the "
                                   + "caller's permissions: approve, reject, pause, resume, takedown, restore")
                           List<String> allowedActions,
                           @Schema(description = "True while the tax gate holds the listing (in review, not live, until tax "
                                   + "review approves its tax details)")
                           boolean heldForTax) {

        static ProductResponse of(Product p) {
            return new ProductResponse(p.id(), p.atin().value(), p.owner().kind().name(), p.owner().orgId(),
                    p.categoryId(), p.categoryRequestId(), p.productTypeCode(), p.title(), p.attributes(),
                    p.images().stream().map(ImageDto::of).toList(), p.variationTheme(),
                    p.variants().stream().map(VariantResponse::of).toList(), p.status().name(), p.moderationNote(),
                    p.createdAt(), p.updatedAt(), p.version(), p.preorderEligible(), p.shipsNow(),
                    ListingDto.of(p.details()), p.allowedActions().stream().map(ProductAction::key).toList(),
                    p.heldForTax());
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
