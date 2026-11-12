package com.afrotransact.offers.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.adapter.in.web.OfferDtos.PackagingDto;
import com.afrotransact.offers.adapter.in.web.OfferDtos.ShipFromDto;
import com.afrotransact.offers.application.OfferBatchService;
import com.afrotransact.offers.application.OfferBatchService.PriceStockBatch;
import com.afrotransact.offers.application.OfferBatchService.PriceStockRow;
import com.afrotransact.offers.application.OfferBatchService.RowResult;
import com.afrotransact.offers.application.OfferBatchService.UpsertBatch;
import com.afrotransact.offers.application.OfferBatchService.UpsertRow;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Batch offer endpoints for bulk uploads (S393, S394). Each row has its own result; good rows are saved. */
@Tag(name = "Offers", description = "Seller offers on existing catalog products (offers:read / offers:write)")
@RestController
@RequestMapping("/v1/offers")
class OfferBatchController {

    private final OfferBatchService batches;

    OfferBatchController(OfferBatchService batches) {
        this.batches = batches;
    }

    @Operation(operationId = "upsertOffers", summary = "Create or change many offers (bulk upload, S393)",
            description = "Up to 500 rows per call, each in its own transaction with its own result. A row creates the "
                    + "offer, or changes the terms of the organisation's open offer on the same ATIN, condition and "
                    + "channel; sending it again changes nothing (UNCHANGED). The same checks as POST /v1/offers apply "
                    + "(selling approval, condition, package, listing cap) and an offer is ACTIVE only with the seller's "
                    + "own RESOLVED tax decision, otherwise HELD (stored TAX_HELD, not for sale). Sellers act for their own "
                    + "organisation (another sellerOrgId is 403 offers.not-your-organisation); service callers name it. "
                    + "One audit row per created, changed or held offer carries importId and the row key.")
    @PostMapping("/batch")
    BatchResponse upsert(@Valid @RequestBody UpsertBatchRequest body) {
        return BatchResponse.of(batches.upsert(body.toCommand()));
    }

    @Operation(operationId = "setPricesAndStock", summary = "Change price, list price and stock of many offers (price "
            + "and quantity feed, S394)",
            description = "Up to 500 rows per call. A row names the offers by sellerSku, or by atin when no SKU is "
                    + "given; blank (null) fields are left as they are, listPriceMinor 0 clears the list price. Only the "
                    + "organisation's own open offers are visible (another seller's SKU answers offers.sku-not-found). "
                    + "Quantity applies to seller-fulfilled offers; a Fulfilled-by-AfroTransact offer answers "
                    + "offers.stock-managed-by-inventory. The status of an offer never changes here: a TAX_HELD offer "
                    + "stays held. A row that changes nothing is UNCHANGED, so uploading the same file again changes "
                    + "nothing. One audit row per changed offer carries importId.")
    @PutMapping("/prices-and-stock")
    BatchResponse pricesAndStock(@Valid @RequestBody PriceStockBatchRequest body) {
        return BatchResponse.of(batches.setPricesAndStock(body.toCommand()));
    }

    @Schema(name = "UpsertOffersRequest")
    record UpsertBatchRequest(@Schema(description = "Staff / service callers only; sellers act for their own "
            + "organisation") String sellerOrgId,
                              @Schema(description = "The upload the rows come from (audit)") UUID importId,
                              @Size(max = 120) @Schema(description = "Who uploaded the file (audit)") String uploadedBy,
                              @NotEmpty @Size(max = 500) List<@Valid UpsertOfferRowDto> rows) {

        UpsertBatch toCommand() {
            return new UpsertBatch(sellerOrgId, importId, uploadedBy, rows.stream().map(UpsertOfferRowDto::toDomain)
                    .toList());
        }
    }

    @Schema(name = "UpsertOfferRow", description = "Offer terms only: page content is never taken from a file")
    record UpsertOfferRowDto(@NotBlank @Size(max = 80) @Schema(description = "Your reference for the row, echoed in "
            + "the result") String rowKey,
                             @NotNull @Pattern(regexp = "AT[0-9A-Z]{8}") String atin,
                             @NotBlank @Size(max = 40) String sellerSku,
                             @Schema(defaultValue = "NEW") OfferCondition condition,
                             @Min(1) long priceMinor, @PositiveOrZero Long listPriceMinor,
                             @PositiveOrZero Long shippingMinor, @NotNull Fulfilment fulfilment,
                             @PositiveOrZero Integer quantity, @Min(0) @Max(30) Integer handlingDays,
                             @Valid ShipFromDto shipFrom, @Size(max = 1000) String conditionNote,
                             @Valid PackagingDto packaging) {

        UpsertRow toDomain() {
            return new UpsertRow(rowKey, atin, sellerSku, condition, priceMinor, listPriceMinor, shippingMinor,
                    fulfilment, quantity, handlingDays, shipFrom == null ? null : shipFrom.toDomain(), conditionNote,
                    packaging == null ? null : packaging.toDomain());
        }
    }

    @Schema(name = "PricesAndStockRequest")
    record PriceStockBatchRequest(String sellerOrgId, UUID importId, @Size(max = 120) String uploadedBy,
                                  @NotEmpty @Size(max = 500) List<@Valid PriceStockRowDto> rows) {

        PriceStockBatch toCommand() {
            return new PriceStockBatch(sellerOrgId, importId, uploadedBy, rows.stream()
                    .map(PriceStockRowDto::toDomain).toList());
        }
    }

    @Schema(name = "PriceStockRow", description = "sellerSku, or atin when there is no SKU; null fields stay as they are")
    record PriceStockRowDto(@NotBlank @Size(max = 80) String rowKey, @Size(max = 40) String sellerSku,
                            @Pattern(regexp = "AT[0-9A-Z]{8}") String atin, @Min(1) Long priceMinor,
                            @Schema(description = "0 clears the list price") @PositiveOrZero Long listPriceMinor,
                            @PositiveOrZero Integer quantity) {

        PriceStockRow toDomain() {
            return new PriceStockRow(rowKey, sellerSku, atin, priceMinor, listPriceMinor, quantity);
        }
    }

    @Schema(name = "OfferBatchResponse")
    record BatchResponse(List<RowResultDto> results, int created, int updated, int unchanged, int held, int failed) {

        static BatchResponse of(List<RowResult> results) {
            return new BatchResponse(results.stream().map(RowResultDto::of).toList(), count(results, "CREATED"),
                    count(results, "UPDATED"), count(results, "UNCHANGED"), count(results, "HELD"),
                    count(results, "FAILED"));
        }

        private static int count(List<RowResult> results, String outcome) {
            return (int) results.stream().filter(r -> r.outcome().name().equals(outcome)).count();
        }
    }

    @Schema(name = "OfferBatchRowResult")
    record RowResultDto(String rowKey, @Schema(allowableValues = {"CREATED", "UPDATED", "UNCHANGED", "HELD", "FAILED"})
                        String outcome, UUID offerId, String atin,
                        @Schema(allowableValues = {"ACTIVE", "PAUSED", "WITHDRAWN", "TAX_HELD"}) String status,
                        String code, String message) {

        static RowResultDto of(RowResult r) {
            return new RowResultDto(r.rowKey(), r.outcome().name(), r.offerId(), r.atin(),
                    r.status() == null ? null : r.status().name(), r.code(), r.message());
        }
    }
}
