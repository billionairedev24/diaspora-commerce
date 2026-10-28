package com.afrotransact.payments.adapter.in.web;

import java.util.List;

import com.afrotransact.payments.adapter.in.web.MembershipDtos.CancelRequest;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.EntitlementsDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.FeeDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.GrantDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.GrantRequest;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.JoinRequest;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.MemberDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.SavingsRequest;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.TierDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.UseDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.WaiverQuoteDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.WaiversRequest;
import com.afrotransact.payments.application.MembershipBenefitsService;
import com.afrotransact.payments.application.MembershipGrants;
import com.afrotransact.payments.application.MembershipService;
import com.afrotransact.payments.application.MembershipService.JoinCommand;
import com.afrotransact.payments.application.MembershipTierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Membership", description = "AfroTransact membership (S323, ADR 0006): configurable tiers with a menu of "
        + "benefits, billed on a saved card. The shopper's own membership, the fee waivers checkout applies, "
        + "entitlements for other services and the free days programs grant.")
@RestController
class MembershipController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final MembershipTierService tiers;
    private final MembershipService memberships;
    private final MembershipBenefitsService benefits;
    private final MembershipGrants grants;

    MembershipController(MembershipTierService tiers, MembershipService memberships,
                         MembershipBenefitsService benefits, MembershipGrants grants) {
        this.tiers = tiers;
        this.memberships = memberships;
        this.benefits = benefits;
        this.grants = grants;
    }

    // ------------------------------------------------------------------ the shopper

    @Operation(operationId = "listMembershipTiers", summary = "The tiers a shopper can join, entry tier first",
            description = "Published tiers only, with their prices, trial and benefits.")
    @GetMapping("/v1/memberships/tiers")
    List<TierDto> publishedTiers() {
        return tiers.published().stream().map(TierDto::of).toList();
    }

    @Operation(operationId = "getMyMembership", summary = "The signed-in shopper's membership",
            description = "The live membership, else the latest that ended; membership is absent for a shopper who "
                    + "never joined. Includes the renewal date, the benefits used, the charges and the refund rule.")
    @GetMapping("/v1/memberships/me")
    MemberDto me() {
        return MemberDto.of(memberships.me());
    }

    @Operation(operationId = "joinMembership", summary = "Join a tier on a saved card",
            description = "A first-time member gets the tier's free trial (one per customer and per card); otherwise "
                    + "the first period is charged now. Idempotent per Idempotency-Key.")
    @PostMapping("/v1/memberships/me/join")
    @ResponseStatus(HttpStatus.CREATED)
    MemberDto join(@RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
                   @Valid @RequestBody JoinRequest body) {
        return MemberDto.of(memberships.join(new JoinCommand(body.tierCode(), body.interval(),
                body.paymentMethodId()), idempotencyKey));
    }

    @Operation(operationId = "changeMembership", summary = "Upgrade, downgrade or switch between monthly and yearly",
            description = "A higher tier is effective now and charges the difference for the rest of the period; a "
                    + "lower tier or another interval is scheduled for the end of the paid period. Naming the current "
                    + "plan cancels a scheduled change.")
    @PostMapping("/v1/memberships/me/change")
    MemberDto change(@RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
                     @Valid @RequestBody JoinRequest body) {
        return MemberDto.of(memberships.change(new JoinCommand(body.tierCode(), body.interval(),
                body.paymentMethodId()), idempotencyKey));
    }

    @Operation(operationId = "cancelMembership", summary = "Cancel the membership",
            description = "No more charges; the benefits run to the end of the paid period. With refund=true the "
                    + "latest fee goes back to the card and the membership ends now, which the refund rule allows "
                    + "within the staff-set window and before any benefit was used (409 otherwise).")
    @PostMapping("/v1/memberships/me/cancel")
    MemberDto cancel(@RequestBody(required = false) CancelRequest body) {
        return MemberDto.of(memberships.cancel(body != null && body.refund()));
    }

    @Operation(operationId = "resumeMembership", summary = "Take back a cancellation or a scheduled plan change")
    @PostMapping("/v1/memberships/me/resume")
    MemberDto resume() {
        return MemberDto.of(memberships.resume());
    }

    @Operation(operationId = "listMyMembershipUses", summary = "The benefits the signed-in member used, newest first")
    @GetMapping("/v1/memberships/me/uses")
    List<UseDto> myUses(@RequestParam(defaultValue = "50") int limit) {
        return memberships.myUses(limit).stream().map(UseDto::of).toList();
    }

    @Operation(operationId = "getMyMembershipEntitlements", summary = "What the signed-in shopper's membership gives now",
            description = "For the member badge and the places that show a benefit.")
    @GetMapping("/v1/memberships/me/entitlements")
    EntitlementsDto myEntitlements() {
        return EntitlementsDto.of(benefits.myEntitlements());
    }

    @Operation(operationId = "quoteMembershipSavings", summary = "Which fees of an order the membership waives",
            description = "For the cart and checkout. A member sees each fee waived; a shopper who is not a member "
                    + "sees what joining would save on this order (upsell).")
    @PostMapping("/v1/memberships/me/savings")
    WaiverQuoteDto savings(@Valid @RequestBody SavingsRequest body) {
        return WaiverQuoteDto.of(benefits.quoteMine(body.itemsMinor(), body.currency(),
                body.fees().stream().map(FeeDto::toDomain).toList()));
    }

    // ------------------------------------------------------------------ services

    @Operation(operationId = "resolveMembershipWaivers", summary = "Fees a customer's membership waives on an order "
            + "(payments:service)",
            description = "The one place the delivery benefits are resolved (ADR 0005 section 3): the order's goods "
                    + "subtotal must reach the benefit's minimum and the waived amount stays within its per-order "
                    + "cap. Pure; nothing is recorded until the payment is created with the waivers.")
    @PostMapping("/v1/memberships/waivers")
    WaiverQuoteDto waivers(@Valid @RequestBody WaiversRequest body) {
        return WaiverQuoteDto.of(benefits.quote(body.customerId(), body.itemsMinor(), body.currency(),
                body.fees().stream().map(FeeDto::toDomain).toList()));
    }

    @Operation(operationId = "getCustomerMembershipEntitlements", summary = "What a customer's membership gives now "
            + "(calling services, or staff with payments:read)",
            description = "Early access hours for pre-order windows and runs, priority support and free returns for "
                    + "support tools, the reward boost for the referral program.")
    @GetMapping("/v1/memberships/customers/{customerId}/entitlements")
    EntitlementsDto entitlements(@PathVariable String customerId) {
        return EntitlementsDto.of(benefits.entitlementsOf(customerId));
    }

    @Operation(operationId = "getCustomerMembership", summary = "A customer's membership as they see it (staff with "
            + "payments:read)")
    @GetMapping("/v1/memberships/customers/{customerId}")
    MemberDto customer(@PathVariable String customerId) {
        return MemberDto.of(memberships.ofCustomer(customerId));
    }

    @Operation(operationId = "grantMembershipDays", summary = "Grant free membership days for a program "
            + "(payments:credit.grant)",
            description = "The referral program grants days instead of credit when staff configure it so. A customer "
                    + "without a membership gets a complimentary one; a member has the days added to the period. "
                    + "Idempotent per Idempotency-Key.")
    @PostMapping("/v1/memberships/grants")
    @ResponseStatus(HttpStatus.CREATED)
    GrantDto grant(@RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
                   @Valid @RequestBody GrantRequest body) {
        return GrantDto.of(grants.grantDaysForProgram(body.customerId(), body.tierCode(), body.days(), body.reason(),
                idempotencyKey));
    }
}
