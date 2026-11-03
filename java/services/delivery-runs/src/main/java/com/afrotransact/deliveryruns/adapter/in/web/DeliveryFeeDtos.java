package com.afrotransact.deliveryruns.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.FeeRuleRef;
import com.afrotransact.deliveryruns.application.FeePreviewCommand;
import com.afrotransact.deliveryruns.application.FeeRuleCommand;
import com.afrotransact.deliveryruns.domain.DeliveryFeeChange;
import com.afrotransact.deliveryruns.domain.DeliveryFeeRule;
import com.afrotransact.deliveryruns.domain.FeeBand;
import com.afrotransact.deliveryruns.domain.FeeMethod;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Wire shapes of the staff delivery fee rules (ADR 0005 §3). */
final class DeliveryFeeDtos {

    private DeliveryFeeDtos() {
    }

    @Schema(name = "DeliveryFeeBand", description = "Addresses fromMiles (inclusive) up to toMiles (exclusive) from the "
            + "hub pay feeMinor")
    record BandBody(@NotNull @PositiveOrZero BigDecimal fromMiles, @NotNull BigDecimal toMiles,
                    @NotNull @PositiveOrZero Long feeMinor) {

        static BandBody of(FeeBand b) {
            return new BandBody(b.fromMiles(), b.toMiles(), b.feeMinor());
        }

        FeeBand toDomain() {
            return new FeeBand(fromMiles, toMiles, feeMinor);
        }
    }

    @Schema(name = "DeliveryFeeRule", description = """
            The delivery fee for one method in one currency; the ETag carries the version for If-Match.
            AFROTRANSACT_DELIVERY has bands; CARRIER the handling, fallback and free-over amounts; PICKUP its fee.""")
    record RuleResponse(UUID id, FeeMethod method, String currency, long version, List<BandBody> bands,
                        long feeMinor, long handlingMinor, long fallbackMinor, Long freeAboveMinor,
                        @Schema(description = "How far AfroTransact delivers (end of the last band); 0 for the "
                                + "other methods") BigDecimal coverageMiles,
                        Instant updatedAt, String updatedBy, String reason) {

        static RuleResponse of(DeliveryFeeRule r) {
            return new RuleResponse(r.id(), r.method(), r.currency(), r.version(),
                    r.bands().stream().map(BandBody::of).toList(), r.feeMinor(), r.handlingMinor(),
                    r.fallbackMinor(), r.freeAboveMinor(), r.coverageMiles(), r.updatedAt(), r.updatedBy(),
                    r.reason());
        }
    }

    @Schema(name = "DeliveryFeeRules")
    record RulesResponse(List<RuleResponse> items) {

        static RulesResponse of(List<DeliveryFeeRule> rules) {
            return new RulesResponse(rules.stream().map(RuleResponse::of).toList());
        }
    }

    @Schema(name = "DeliveryFeeRuleRequest", description = """
            The rule's new numbers; a method ignores what it does not use. The reason is required and is logged with
            who and when.""")
    record RuleRequest(@Valid @Size(max = DeliveryFeeRule.MAX_BANDS) List<BandBody> bands, @PositiveOrZero Long feeMinor,
                       @PositiveOrZero Long handlingMinor, @PositiveOrZero Long fallbackMinor,
                       @PositiveOrZero Long freeAboveMinor, @NotBlank @Size(max = 500) String reason) {

        FeeRuleCommand toCommand() {
            return new FeeRuleCommand(bands == null ? List.of() : bands.stream().map(BandBody::toDomain).toList(),
                    feeMinor, handlingMinor, fallbackMinor, freeAboveMinor, reason);
        }
    }

    @Schema(name = "DeliveryFeeChange", description = "One entry of the audit trail: the rule before and after, who, "
            + "when and why")
    record ChangeResponse(UUID id, FeeMethod method, String currency, long version, RuleResponse before,
                          RuleResponse after, String actor, String reason, Instant at) {

        static ChangeResponse of(DeliveryFeeChange c) {
            return new ChangeResponse(c.id(), c.method(), c.currency(), c.version(),
                    c.before() == null ? null : RuleResponse.of(c.before()), RuleResponse.of(c.after()), c.actor(),
                    c.reason(), c.at());
        }
    }

    @Schema(name = "DeliveryFeeChanges")
    record ChangesResponse(List<ChangeResponse> items) {

        static ChangesResponse of(List<DeliveryFeeChange> changes) {
            return new ChangesResponse(changes.stream().map(ChangeResponse::of).toList());
        }
    }

    @Schema(name = "DeliveryFeePreviewRequest", description = "What would a customer pay?")
    record PreviewRequest(@NotNull FeeMethod method, @NotBlank @Size(max = 3) String currency,
                          @Schema(description = "AFROTRANSACT_DELIVERY: miles from the hub (absent: the window's own "
                                  + "distance, else the farthest band)") @PositiveOrZero BigDecimal distanceMiles,
                          @Schema(description = "Apply this window's own fee and distance") UUID windowId,
                          @Schema(description = "CARRIER: the carrier-gateway quote, if any") @PositiveOrZero
                          Long carrierQuoteMinor,
                          @Schema(description = "CARRIER: goods subtotal (free-shipping threshold)") @PositiveOrZero
                          Long itemsMinor) {

        FeePreviewCommand toCommand() {
            return new FeePreviewCommand(method, currency, distanceMiles, windowId, carrierQuoteMinor, itemsMinor);
        }
    }

    @Schema(name = "DeliveryFeeResolution", description = """
            applies is false when no rule can price it: no rule for the method and currency, or the address is beyond
            how far AfroTransact delivers (the order ships by carrier).""")
    record ResolutionResponse(boolean applies, FeeRuleRef fee) {

        static ResolutionResponse of(Optional<FeeSnapshot> fee) {
            return new ResolutionResponse(fee.isPresent(), fee.map(FeeRuleRef::of).orElse(null));
        }
    }

    @Schema(name = "CarrierFeeRequest", description = "The carrier rate for an order shipped beyond coverage")
    record CarrierRequest(@NotBlank @Size(max = 3) String currency,
                          @Schema(description = "The carrier-gateway quote, if there is one") @PositiveOrZero
                          Long carrierQuoteMinor,
                          @NotNull @PositiveOrZero Long itemsMinor) {
    }
}
