package com.afrotransact.offers.adapter.in.web;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.adapter.in.web.CouponDtos.CartRequest;
import com.afrotransact.offers.adapter.in.web.CouponDtos.CouponResponse;
import com.afrotransact.offers.adapter.in.web.CouponDtos.CreateCouponRequest;
import com.afrotransact.offers.adapter.in.web.CouponDtos.PublicCouponResponse;
import com.afrotransact.offers.adapter.in.web.CouponDtos.QuoteResponse;
import com.afrotransact.offers.adapter.in.web.CouponDtos.RedemptionResponse;
import com.afrotransact.offers.adapter.in.web.CouponDtos.StatusRequest;
import com.afrotransact.offers.application.CouponService;
import com.afrotransact.offers.application.RedemptionResult;
import com.afrotransact.offers.domain.Coupon;
import com.afrotransact.offers.domain.CouponFunding;
import com.afrotransact.offers.domain.CouponStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Coupons", description = "Coupons & promotions: manage, clip, validate and redeem")
@RestController
class CouponController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CouponService coupons;

    CouponController(CouponService coupons) {
        this.coupons = coupons;
    }

    @Operation(summary = "Create a coupon",
            description = "PLATFORM funding: staff with coupons:platform:write. SELLER funding: coupons:write, funded "
                    + "by (and only applicable to) the caller's organisation.")
    @PostMapping("/v1/coupons")
    ResponseEntity<CouponResponse> create(@Valid @RequestBody CreateCouponRequest body) {
        Coupon created = coupons.create(body.toCommand());
        return ResponseEntity.created(URI.create("/v1/coupons/" + created.id())).body(CouponResponse.of(created));
    }

    @Operation(summary = "List coupons (staff: all, filterable; sellers: their own)")
    @GetMapping("/v1/coupons")
    List<CouponResponse> list(@RequestParam(required = false) CouponFunding funding,
                              @RequestParam(required = false) String fundedByOrg,
                              @RequestParam(required = false) CouponStatus status) {
        return coupons.list(funding, fundedByOrg, status).stream().map(CouponResponse::of).toList();
    }

    @Operation(summary = "Get a coupon")
    @GetMapping("/v1/coupons/{id}")
    CouponResponse get(@PathVariable UUID id) {
        return CouponResponse.of(coupons.get(id));
    }

    @Operation(summary = "Pause, activate or expire a coupon")
    @PostMapping("/v1/coupons/{id}/status")
    CouponResponse status(@PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
        return CouponResponse.of(coupons.changeStatus(id, body.status()));
    }

    @Operation(summary = "Clip a coupon (signed-in customer; idempotent)")
    @PostMapping("/v1/coupons/{code}/clip")
    CouponResponse clip(@PathVariable String code) {
        return CouponResponse.of(coupons.clip(code));
    }

    @Operation(summary = "Price a coupon against a cart (cart / checkout); nothing is recorded",
            description = "Always 200: applicable=false carries a stable rejection code (NOT_FOUND, EXPIRED, "
                    + "BELOW_MINIMUM_SPEND, CUSTOMER_LIMIT_REACHED …).")
    @PostMapping("/v1/coupons/validate")
    QuoteResponse validate(@Valid @RequestBody CartRequest body) {
        return QuoteResponse.of(coupons.validate(body.toCommand()));
    }

    @Operation(summary = "Redeem a coupon on an order (orders-service; SERVICE / STAFF principal)",
            description = "Requires Idempotency-Key. Same key + same body → the original redemption (200, replayed); "
                    + "same key + different body → 409 offers.idempotency-key-reused; not applicable → 422 "
                    + "offers.coupon-not-applicable with reason.")
    @PostMapping("/v1/coupons/redemptions")
    ResponseEntity<RedemptionResponse> redeem(
            @Parameter(description = "Unique per order and coupon, e.g. the order id")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CartRequest body) {
        RedemptionResult result = coupons.redeem(idempotencyKey, body.toCommand());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(RedemptionResponse.of(result));
    }

    @Operation(summary = "Release a redemption (order cancelled / payment failed); idempotent")
    @PostMapping("/v1/coupons/redemptions/{id}/release")
    RedemptionResponse release(@PathVariable UUID id) {
        return RedemptionResponse.of(coupons.release(id));
    }

    @Operation(summary = "Clip-to-apply coupons that cover an offer of this ATIN (product page)")
    @SecurityRequirements
    @Tag(name = "Public offers")
    @GetMapping("/v1/public/coupons/clippable")
    ResponseEntity<List<PublicCouponResponse>> clippable(@RequestParam String atin,
                                                         @RequestParam(defaultValue = "USD") String currency) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(coupons.clippable(atin, currency).stream().map(PublicCouponResponse::of).toList());
    }
}
