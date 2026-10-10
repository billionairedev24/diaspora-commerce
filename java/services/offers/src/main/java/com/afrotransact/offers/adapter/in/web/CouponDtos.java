package com.afrotransact.offers.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.application.CouponCartCommand;
import com.afrotransact.offers.application.CouponQuote;
import com.afrotransact.offers.application.CreateCouponCommand;
import com.afrotransact.offers.application.RedemptionResult;
import com.afrotransact.offers.domain.Coupon;
import com.afrotransact.offers.domain.CouponFunding;
import com.afrotransact.offers.domain.CouponRedemption;
import com.afrotransact.offers.domain.CouponRejection;
import com.afrotransact.offers.domain.CouponScope;
import com.afrotransact.offers.domain.CouponStatus;
import com.afrotransact.offers.domain.CouponType;
import com.afrotransact.offers.domain.LineDiscount;
import com.afrotransact.offers.domain.RedemptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Coupon management, validation and redemption bodies. */
final class CouponDtos {

    private CouponDtos() {
    }

    @Schema(name = "CreateCouponRequest")
    record CreateCouponRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9]{3,20}") @Schema(example = "WELCOME10") String code,
            @Size(max = 300) String description,
            @NotNull CouponType type,
            @PositiveOrZero @Schema(description = "Percent (1–100) for PERCENT, minor units for FIXED, 0 for "
                    + "FREE_SHIPPING", example = "10") long value,
            @Pattern(regexp = "[A-Za-z]{3}") @Schema(defaultValue = "USD") String currency,
            @Schema(description = "Cap for PERCENT coupons") Long maxDiscountMinor,
            @NotNull CouponScope scope,
            @Schema(description = "Category id (CATEGORY), seller org id (SELLER) or ATIN (PRODUCT)") String scopeRef,
            @PositiveOrZero long minSpendMinor,
            @PositiveOrZero @Schema(description = "0 = unlimited") int totalLimit,
            @PositiveOrZero @Schema(description = "0 = unlimited") int perCustomerLimit,
            @Schema(description = "Customers must clip the coupon before it applies") boolean clipRequired,
            @NotNull @Schema(description = "PLATFORM = AfroTransact-funded (staff); SELLER = seller-funded")
            CouponFunding funding,
            @Schema(description = "Staff only, for seller-funded coupons; defaults to the caller's org")
            String fundedByOrg,
            @Schema(description = "Defaults to now") Instant startsAt,
            @Schema(description = "Exclusive end; null = open-ended") Instant endsAt) {

        CreateCouponCommand toCommand() {
            return new CreateCouponCommand(code, description, type, value, currency, maxDiscountMinor, scope,
                    scopeRef, minSpendMinor, totalLimit, perCustomerLimit, clipRequired, funding, fundedByOrg,
                    startsAt, endsAt);
        }
    }

    @Schema(name = "Coupon")
    record CouponResponse(UUID id, String code, String description, CouponType type, long value, String currency,
                          Long maxDiscountMinor, CouponScope scope, String scopeRef, long minSpendMinor,
                          int totalLimit, int perCustomerLimit, boolean clipRequired, CouponFunding funding,
                          String fundedByOrg, Instant startsAt, Instant endsAt, CouponStatus status,
                          long redeemedCount, Instant createdAt, Instant updatedAt, long version) {

        static CouponResponse of(Coupon c) {
            return new CouponResponse(c.id(), c.code(), c.description(), c.type(), c.value(), c.currency(),
                    c.maxDiscountMinor(), c.scope(), c.scopeRef(), c.minSpendMinor(), c.totalLimit(),
                    c.perCustomerLimit(), c.clipRequired(), c.funding(), c.fundedByOrg(), c.startsAt(), c.endsAt(),
                    c.status(), c.redeemedCount(), c.createdAt(), c.updatedAt(), c.version());
        }
    }

    @Schema(name = "PublicCoupon", description = "A clip-to-apply coupon shown on the product page")
    record PublicCouponResponse(String code, String description, CouponType type, long value, String currency,
                                Long maxDiscountMinor, long minSpendMinor, CouponScope scope, Instant endsAt) {

        static PublicCouponResponse of(Coupon c) {
            return new PublicCouponResponse(c.code(), c.description(), c.type(), c.value(), c.currency(),
                    c.maxDiscountMinor(), c.minSpendMinor(), c.scope(), c.endsAt());
        }
    }

    @Schema(name = "CouponStatusRequest")
    record StatusRequest(@NotNull CouponStatus status) {
    }

    @Schema(name = "CouponCartLine")
    record LineDto(@NotNull UUID offerId, @Min(1) @Max(999) int quantity) {
    }

    @Schema(name = "CouponCartRequest", description = "Prices come from the offers; only quantities are trusted")
    record CartRequest(@NotBlank @Size(max = 20) String code,
                       @Schema(description = "Service callers: the customer; customers act for themselves")
                       String customerId,
                       @Schema(description = "Required to redeem") String orderId,
                       @Pattern(regexp = "[A-Za-z]{3}") @Schema(defaultValue = "USD") String currency,
                       @NotEmpty @Size(max = 100) List<@Valid LineDto> lines,
                       @PositiveOrZero @Schema(description = "Shipping total (FREE_SHIPPING discounts it)")
                       long shippingMinor,
                       @Schema(description = "Customer has no previous order (FIRST_ORDER coupons)")
                       boolean firstOrder) {

        CouponCartCommand toCommand() {
            return new CouponCartCommand(code, customerId, orderId, currency == null ? "USD" : currency,
                    lines.stream().map(l -> new CouponCartCommand.Line(l.offerId(), l.quantity())).toList(),
                    shippingMinor, firstOrder);
        }
    }

    @Schema(name = "CouponQuote")
    record QuoteResponse(String code, UUID couponId, boolean applicable, CouponRejection rejection, String message,
                         String currency, long eligibleSubtotalMinor, long discountMinor, long shippingDiscountMinor,
                         long totalDiscountMinor, CouponFunding funding, String fundedByOrg,
                         List<LineDiscount> lines) {

        static QuoteResponse of(CouponQuote q) {
            return new QuoteResponse(q.code(), q.couponId(), q.applicable(), q.rejection(), q.message(),
                    q.currency(), q.eligibleSubtotalMinor(), q.discountMinor(), q.shippingDiscountMinor(),
                    q.discountMinor() + q.shippingDiscountMinor(), q.funding(), q.fundedByOrg(), q.lines());
        }
    }

    @Schema(name = "CouponRedemption", description = "funding / fundedByOrg / lines drive settlement")
    record RedemptionResponse(UUID id, UUID couponId, String code, String customerId, String orderId,
                              String currency, long discountMinor, long shippingDiscountMinor,
                              long totalDiscountMinor, CouponFunding funding, String fundedByOrg,
                              List<LineDiscount> lines, RedemptionStatus status, Instant createdAt,
                              Instant releasedAt, boolean replayed) {

        static RedemptionResponse of(RedemptionResult result) {
            CouponRedemption r = result.redemption();
            return new RedemptionResponse(r.id(), r.couponId(), r.code(), r.customerId(), r.orderId(), r.currency(),
                    r.discountMinor(), r.shippingDiscountMinor(), r.discountMinor() + r.shippingDiscountMinor(),
                    r.funding(), r.fundedByOrg(), r.lines(), r.status(), r.createdAt(), r.releasedAt(),
                    result.replayed());
        }
    }
}
