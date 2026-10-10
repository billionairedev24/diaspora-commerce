package com.afrotransact.taxreturns.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.application.ReturnEligibility;
import com.afrotransact.taxreturns.domain.returns.InspectionOutcome;
import com.afrotransact.taxreturns.domain.returns.ItemCondition;
import com.afrotransact.taxreturns.domain.returns.LineEligibility;
import com.afrotransact.taxreturns.domain.returns.RefundBreakdown;
import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.returns.ReturnMethod;
import com.afrotransact.taxreturns.domain.returns.ReturnReason;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaLine;
import com.afrotransact.taxreturns.domain.returns.SellerReturnPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies for returns and seller return policies. */
final class ReturnDtos {

    private ReturnDtos() {
    }

    @Schema(name = "Address", description = "Postal address (customer origin or seller / FC return address)")
    record AddressDto(@Size(max = 120) String name, @Size(max = 200) String line1, @Size(max = 200) String line2,
                      @Size(max = 120) String city, @Size(max = 10) String state, @Size(max = 20) String postalCode,
                      @Size(max = 2) String country, @Size(max = 40) String phone) {

        ReturnAddress toDomain() {
            return new ReturnAddress(name, line1, line2, city, state, postalCode, country, phone);
        }

        static AddressDto of(ReturnAddress a) {
            return a == null ? null : new AddressDto(a.name(), a.line1(), a.line2(), a.city(), a.state(),
                    a.postalCode(), a.country(), a.phone());
        }
    }

    @Schema(name = "CreateReturnLine")
    record CreateReturnLine(@NotBlank String lineId, @Min(1) int quantity, @NotNull ReturnReason reason,
                            @Size(max = 500) String comment,
                            @Schema(description = "media-service asset ids of evidence photos (required for damaged, "
                                    + "defective, wrong item, expired or spoiled)")
                            @Size(max = 6) List<@NotBlank @Size(max = 120) String> evidenceAssetIds) {
    }

    @Schema(name = "CreateReturn", description = "Items of one delivered shipment, the reason per item and the method")
    record CreateReturn(@NotNull UUID orderId, @NotNull UUID shipmentId, @NotNull ReturnMethod method,
                        @NotEmpty @Size(max = 100) List<@Valid CreateReturnLine> lines,
                        @Schema(description = "Required for DROP_OFF: where the parcel ships from") @Valid
                        AddressDto returnFrom) {
    }

    @Schema(name = "Decision")
    record Decision(@Size(max = 1000) String note) {
    }

    @Schema(name = "Pickup")
    record Pickup(@Schema(description = "6-digit code the customer shows the driver") @Size(max = 10)
                  String handoverCode) {
    }

    @Schema(name = "InspectLine")
    record InspectLine(@NotBlank String lineId, @NotNull InspectionOutcome outcome,
                       @Schema(description = "Default: RESTOCK when passed, DAMAGED when failed") ItemCondition condition,
                       @Size(max = 1000) String note) {
    }

    @Schema(name = "Inspection")
    record Inspection(@NotEmpty @Size(max = 100) List<@Valid InspectLine> lines, @Size(max = 1000) String note) {
    }

    @Schema(name = "RmaLine")
    record RmaLineResponse(String lineId, String atin, String title, int quantity, long unitPriceMinor,
                           ReturnReason reason, String comment, List<String> evidenceAssetIds, String outcome,
                           String condition, String stockStatus, String inspectionNote) {

        static RmaLineResponse of(RmaLine l) {
            return new RmaLineResponse(l.lineId(), l.atin(), l.title(), l.quantity(), l.unitPriceMinor(), l.reason(),
                    l.comment(), l.evidenceAssetIds(), l.outcome() == null ? null : l.outcome().name(),
                    l.condition() == null ? null : l.condition().name(), l.stockStatus().name(), l.inspectionNote());
        }
    }

    @Schema(name = "Rma", description = "A return (RMA). handoverCode is only shown to the customer and staff.")
    record RmaResponse(UUID id, String number, UUID orderId, String orderNumber, UUID shipmentId, String customerId,
                       String sellerOrgId, String fulfilmentType, String deliveryMethod, ReturnMethod method,
                       String status, String refundState, boolean autoApproved, Instant approvalDueAt,
                       boolean refundAtFirstScan, String currency, long estimateMinor, String carrier,
                       String trackingNumber, String labelUrl,
                       @Schema(description = "Payload the app renders as the drop-off / handover QR code")
                       String qrPayload, String handoverCode, Instant dropOffBy, String decisionNote,
                       boolean cancellable, List<RmaLineResponse> lines, Instant createdAt, Instant updatedAt) {

        static RmaResponse of(Rma r, boolean showCode) {
            return new RmaResponse(r.id(), r.number(), r.orderId(), r.orderNumber(), r.shipmentId(), r.customerId(),
                    r.sellerOrgId(), r.fulfilment().name(), r.delivery().name(), r.method(), r.status().name(),
                    r.refundState().name(), r.autoApproved(), r.approvalDueAt(), r.refundAtFirstScan(), r.currency(),
                    r.estimateMinor(), r.label() == null ? null : r.label().carrier(),
                    r.label() == null ? null : r.label().trackingNumber(),
                    r.label() == null ? null : r.label().documentUrl(), r.qrPayload(),
                    showCode ? r.handoverCode() : null, r.dropOffBy(), r.decisionNote(), r.status().cancellable(),
                    r.lines().stream().map(RmaLineResponse::of).toList(), r.createdAt(), r.updatedAt());
        }
    }

    @Schema(name = "RefundLineAmount")
    record RefundLineResponse(String lineId, int quantity, long itemsMinor, long shippingMinor, long taxMinor,
                              long restockingFeeMinor, long totalMinor) {

        static RefundLineResponse of(RefundLineAmount l) {
            return new RefundLineResponse(l.lineId(), l.quantity(), l.itemsMinor(), l.shippingMinor(), l.taxMinor(),
                    l.restockingFeeMinor(), l.totalMinor());
        }
    }

    @Schema(name = "RefundQuote", description = "Refund estimate: items + proportional tax + shipping when the seller "
            + "is at fault − restocking fee")
    record RefundQuoteResponse(long itemsMinor, long shippingMinor, long taxMinor, long restockingFeeMinor,
                               long totalMinor, List<RefundLineResponse> lines) {

        static RefundQuoteResponse of(RefundBreakdown b) {
            return new RefundQuoteResponse(b.itemsMinor(), b.shippingMinor(), b.taxMinor(), b.restockingFeeMinor(),
                    b.totalMinor(), b.lines().stream().map(RefundLineResponse::of).toList());
        }
    }

    @Schema(name = "LineEligibility")
    record LineEligibilityResponse(String lineId, String atin, String title, int quantity, long unitPriceMinor,
                                   boolean eligible,
                                   @Schema(description = "Food / perishables: report a problem, no return")
                                   boolean reportProblemOnly, boolean sellerFaultOnly, int maxQuantity,
                                   Instant windowEndsAt, List<ReturnMethod> methods, String ineligibleCode) {

        static LineEligibilityResponse of(ReturnEligibility.Line l) {
            LineEligibility e = l.eligibility();
            return new LineEligibilityResponse(l.lineId(), l.atin(), l.title(), l.quantity(), l.unitPriceMinor(),
                    e.eligible(), e.reportProblemOnly(), e.sellerFaultOnly(), e.maxQuantity(), e.windowEndsAt(),
                    e.methods().stream().sorted().toList(), e.ineligibleCode());
        }
    }

    @Schema(name = "ShipmentEligibility")
    record ShipmentEligibilityResponse(UUID shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                                       String deliveryMethod, Instant deliveredAt,
                                       List<LineEligibilityResponse> lines) {

        static ShipmentEligibilityResponse of(ReturnEligibility.Shipment s) {
            return new ShipmentEligibilityResponse(s.shipmentId(), s.sellerOrgId(), s.sellerName(),
                    s.fulfilment().name(), s.delivery().name(), s.deliveredAt(),
                    s.lines().stream().map(LineEligibilityResponse::of).toList());
        }
    }

    @Schema(name = "ReturnEligibility")
    record EligibilityResponse(UUID orderId, String orderNumber, String currency,
                               List<ShipmentEligibilityResponse> shipments) {

        static EligibilityResponse of(ReturnEligibility e) {
            return new EligibilityResponse(e.orderId(), e.orderNumber(), e.currency(),
                    e.shipments().stream().map(ShipmentEligibilityResponse::of).toList());
        }
    }

    @Schema(name = "SellerReturnPolicy")
    record PolicyDto(@Min(14) @Max(365) int windowDays,
                     @Schema(description = "Restocking fee for no-fault returns, basis points (max 2000)")
                     @Min(0) @Max(2000) int restockingFeeBps,
                     boolean acceptsReturns, boolean refundAtFirstScan, @Valid AddressDto returnAddress,
                     @Schema(description = "Optimistic-lock version from the last read; omit to create")
                     Long version) {

        SellerReturnPolicy toDomain(String orgId) {
            return new SellerReturnPolicy(orgId, windowDays, restockingFeeBps, acceptsReturns, refundAtFirstScan,
                    returnAddress == null ? null : returnAddress.toDomain(), version == null ? -1L : version);
        }

        static PolicyDto of(SellerReturnPolicy p) {
            return new PolicyDto(p.windowDays(), p.restockingFeeBps(), p.acceptsReturns(), p.refundAtFirstScan(),
                    AddressDto.of(p.returnAddress()), p.version() < 0 ? null : p.version());
        }
    }
}
