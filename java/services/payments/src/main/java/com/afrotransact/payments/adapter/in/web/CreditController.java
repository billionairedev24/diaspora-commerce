package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.CreditService;
import com.afrotransact.payments.application.CreditService.AdjustCommand;
import com.afrotransact.payments.application.CreditService.Balance;
import com.afrotransact.payments.application.CreditService.CustomerCredit;
import com.afrotransact.payments.application.CreditService.GrantCommand;
import com.afrotransact.payments.application.CreditService.Liabilities;
import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.CreditAdjustment;
import com.afrotransact.payments.domain.CreditEntry;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.domain.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

@Tag(name = "Credit", description = "AfroTransact credit: a per-customer append-only ledger (grant, spend, refund, "
        + "adjustment). Credit only buys products and services; there is no cash-out, payout, card refund or "
        + "transfer of credit anywhere in this API (ADR 0006).")
@RestController
class CreditController {

    private final CreditService credit;

    CreditController(CreditService credit) {
        this.credit = credit;
    }

    // ------------------------------------------------------------------ DTOs

    @Schema(name = "CreditBalance")
    record BalanceResponse(String customerId, long balanceMinor, String currency) {

        static BalanceResponse of(Balance b) {
            return new BalanceResponse(b.customerId(), b.balance().minor(), b.balance().currency());
        }
    }

    @Schema(name = "CreditEntry", description = "One line of the ledger; the balance is the sum of amountMinor")
    record EntryResponse(UUID id, String customerId,
                         @Schema(allowableValues = {"GRANT", "SPEND", "REFUND", "ADJUSTMENT"}) String kind,
                         @Schema(description = "Signed: credit added is positive, credit spent negative")
                         long amountMinor, String currency,
                         @Schema(allowableValues = {"REFERRAL", "GIFT_CARD", "MEMBERSHIP", "REFUND", "STAFF", "PROMO",
                                 "ORDER"}) String source,
                         String reason, @Schema(description = "Who did it: the customer, a staff member or a service")
                         String actor, String orderId, String reference, long balanceAfterMinor,
                         Instant createdAt) {

        static EntryResponse of(CreditEntry e) {
            return new EntryResponse(e.id(), e.customerId(), e.kind().name(), e.amountMinor(), e.currency(),
                    e.source().name(), e.reason(), e.actor(), e.orderId(), e.reference(), e.balanceAfterMinor(),
                    e.createdAt());
        }

        /** What the shopper sees: the ledger without the internal actor. */
        static EntryResponse forCustomer(CreditEntry e) {
            return new EntryResponse(e.id(), e.customerId(), e.kind().name(), e.amountMinor(), e.currency(),
                    e.source().name(), e.reason(), null, e.orderId(), e.reference(), e.balanceAfterMinor(),
                    e.createdAt());
        }
    }

    @Schema(name = "CreditAdjustment")
    record AdjustmentResponse(UUID id, String customerId, long amountMinor, String currency,
                              @Schema(allowableValues = {"STAFF", "PROMO"}) String source, String reason,
                              @Schema(allowableValues = {"PROPOSED", "POSTED", "REJECTED"}) String status,
                              String proposedBy, Instant proposedAt, String decidedBy, Instant decidedAt,
                              String decisionNote, UUID entryId) {

        static AdjustmentResponse of(CreditAdjustment a) {
            return new AdjustmentResponse(a.id(), a.customerId(), a.amount().minor(), a.amount().currency(),
                    a.source().name(), a.reason(), a.status().name(), a.proposedBy(), a.proposedAt(), a.decidedBy(),
                    a.decidedAt(), a.decisionNote(), a.entryId());
        }
    }

    @Schema(name = "CustomerCredit")
    record CustomerCreditResponse(BalanceResponse balance, List<EntryResponse> entries,
                                  List<AdjustmentResponse> adjustments) {

        static CustomerCreditResponse of(CustomerCredit c) {
            return new CustomerCreditResponse(BalanceResponse.of(c.balance()),
                    c.entries().stream().map(EntryResponse::of).toList(),
                    c.adjustments().stream().map(AdjustmentResponse::of).toList());
        }
    }

    @Schema(name = "CreditLiabilities", description = "What AfroTransact owes in credit and in unredeemed gift cards, "
            + "separately. creditOnOpenOrdersMinor is credit spent on orders that are not shipped yet.")
    record LiabilitiesResponse(String currency, long creditOutstandingMinor, long accountsWithCredit,
                               Map<String, Long> flowsMinor, long giftCardsOutstandingMinor,
                               long giftCardsOutstandingCount, long giftCardsRedeemedMinor,
                               long giftCardsVoidedMinor, long ledgerCreditMinor, long ledgerGiftCardsMinor,
                               long creditOnOpenOrdersMinor) {

        static LiabilitiesResponse of(Liabilities l) {
            return new LiabilitiesResponse(l.currency(), l.creditOutstandingMinor(), l.accountsWithCredit(),
                    l.flowsMinor(), l.giftCardsOutstandingMinor(), l.giftCardsOutstandingCount(),
                    l.giftCardsRedeemedMinor(), l.giftCardsVoidedMinor(), l.ledgerCreditMinor(),
                    l.ledgerGiftCardsMinor(), l.creditOnOpenOrdersMinor());
        }
    }

    @Schema(name = "CreditGrantRequest")
    record GrantRequest(
            @NotBlank @Size(max = 120) String customerId,
            @NotNull @Schema(description = "Above zero") Long amountMinor,
            @NotNull @Schema(allowableValues = {"REFERRAL", "MEMBERSHIP", "PROMO"}) CreditSource source,
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 80) String orderId,
            @Size(max = 120) @Schema(description = "The referral, membership period or promotion behind it")
            String reference) {
    }

    @Schema(name = "CreditAdjustRequest")
    record AdjustRequest(
            @NotBlank @Size(max = 120) String customerId,
            @NotNull @Schema(description = "Positive adds credit, negative takes it back (never below zero)")
            Long amountMinor,
            @NotNull @Schema(allowableValues = {"STAFF", "PROMO"}) CreditSource source,
            @NotBlank @Size(max = 500) @Schema(description = "At least 15 characters") String reason) {
    }

    @Schema(name = "CreditDecisionRequest")
    record DecisionRequest(@Size(max = 500) String note) {
    }

    // ------------------------------------------------------------------ the shopper

    @Operation(summary = "The signed-in shopper's credit balance")
    @GetMapping("/v1/credit/me")
    BalanceResponse myBalance() {
        return BalanceResponse.of(credit.myBalance());
    }

    @Operation(summary = "The signed-in shopper's credit history, newest first",
            description = "before pages backwards (the createdAt of the last entry seen).")
    @GetMapping("/v1/credit/me/entries")
    List<EntryResponse> myEntries(@RequestParam(required = false) Instant before,
                                  @RequestParam(defaultValue = "50") int limit) {
        return credit.myEntries(before, limit).stream().map(EntryResponse::forCustomer).toList();
    }

    // ------------------------------------------------------------------ services and staff

    @Operation(summary = "One customer's credit balance (payments:service for checkout, or staff with payments:read)")
    @GetMapping("/v1/credit/customers/{customerId}/balance")
    BalanceResponse balanceOf(@PathVariable String customerId) {
        return BalanceResponse.of(credit.balanceOf(customerId));
    }

    @Operation(summary = "A customer's credit: balance, history and adjustments (staff with payments:read)")
    @GetMapping("/v1/credit/customers/{customerId}")
    CustomerCreditResponse customer(@PathVariable String customerId, @RequestParam(defaultValue = "100") int limit) {
        return CustomerCreditResponse.of(credit.customer(customerId, limit));
    }

    @Operation(summary = "Grant credit for a program (referral, membership, promotion; payments:credit.grant)",
            description = "Idempotent per Idempotency-Key: a replay returns the first entry and adds nothing. There "
                    + "is no matching operation to take credit out or to move it between customers.")
    @PostMapping("/v1/credit/grants")
    @ResponseStatus(HttpStatus.CREATED)
    EntryResponse grant(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 160) String idempotencyKey,
                        @Valid @RequestBody GrantRequest body) {
        return EntryResponse.of(credit.grant(new GrantCommand(body.customerId(), Money.of(body.amountMinor(), "USD"),
                body.source(), body.reason(), body.orderId(), body.reference(), idempotencyKey)));
    }

    // ------------------------------------------------------------------ staff adjustments

    @Operation(summary = "Staff grant or correction of a customer's credit (ledger:adjust)",
            description = "A reason is required. Up to the limit set in Settings, Money, it is posted at once; above "
                    + "it, it is PROPOSED and a different person with ledger:approve posts it (four eyes).")
    @PostMapping("/v1/credit/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    AdjustmentResponse adjust(@Valid @RequestBody AdjustRequest body) {
        return AdjustmentResponse.of(credit.adjust(new AdjustCommand(body.customerId(),
                Money.of(body.amountMinor(), "USD"), body.source(), body.reason())));
    }

    @Operation(summary = "Credit adjustments, newest first (payments:read)")
    @GetMapping("/v1/credit/adjustments")
    List<AdjustmentResponse> adjustments(@RequestParam(required = false) AdjustmentStatus status,
                                         @RequestParam(required = false) String customerId,
                                         @RequestParam(defaultValue = "100") int limit) {
        return credit.adjustments(status, customerId, limit).stream().map(AdjustmentResponse::of).toList();
    }

    @Operation(summary = "Approve a proposed credit adjustment (ledger:approve; never your own)")
    @PostMapping("/v1/credit/adjustments/{id}/approve")
    AdjustmentResponse approve(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest body) {
        return AdjustmentResponse.of(credit.approve(id, body == null ? null : body.note()));
    }

    @Operation(summary = "Reject a proposed credit adjustment with the reason (ledger:approve)")
    @PostMapping("/v1/credit/adjustments/{id}/reject")
    AdjustmentResponse reject(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest body) {
        return AdjustmentResponse.of(credit.reject(id, body == null ? null : body.note()));
    }

    @Operation(summary = "Credit and gift card liabilities for finance (payments:read)",
            description = "Credit owed to customers and unredeemed gift cards, apart, with the general-ledger "
                    + "accounts 2600 and 2650 beside them.")
    @GetMapping("/v1/credit/liabilities")
    LiabilitiesResponse liabilities() {
        return LiabilitiesResponse.of(credit.liabilities());
    }
}
