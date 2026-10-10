package com.afrotransact.offers.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.application.BuyBoxExplanation;
import com.afrotransact.offers.domain.BuyBoxReason;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Ineligibility;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferEvaluation;
import com.afrotransact.offers.domain.ScoreComponent;
import com.afrotransact.offers.domain.SellerStanding;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/** Buy box explanation and seller standing bodies for seller center and the staff console. */
final class BuyBoxDtos {

    private BuyBoxDtos() {
    }

    @Schema(name = "EvaluatedOffer")
    record EvaluatedOfferDto(UUID offerId, String sellerOrgId, int rank, double score, boolean buyBoxEligible,
                             boolean listable, List<Ineligibility> ineligibility, long priceMinor,
                             long shippingMinor, long landedPriceMinor, Fulfilment fulfilment,
                             OfferCondition condition, int availableQty, int deliveryDays,
                             List<ScoreComponent> components) {

        static EvaluatedOfferDto of(OfferEvaluation e) {
            return new EvaluatedOfferDto(e.candidate().offerId(), e.candidate().sellerOrgId(), e.rank(), e.score(),
                    e.buyBoxEligible(), e.listable(), e.ineligibility(), e.candidate().priceMinor(),
                    e.candidate().shippingMinor(), e.candidate().landedPriceMinor(), e.candidate().fulfilment(),
                    e.candidate().condition(), e.candidate().availableQty(), e.candidate().deliveryDays(),
                    e.components());
        }
    }

    @Schema(name = "BuyBoxExplanation", description = "Sellers see the winner and their own offers; staff see all")
    record ExplanationResponse(String atin, String currency, UUID winnerOfferId, List<BuyBoxReason> reasons,
                               List<EvaluatedOfferDto> offers) {

        static ExplanationResponse of(BuyBoxExplanation e) {
            return new ExplanationResponse(e.atin(), e.currency(), e.winnerOfferId(), e.reasons(),
                    e.evaluations().stream().map(EvaluatedOfferDto::of).toList());
        }
    }

    @Schema(name = "SellerStanding")
    record StandingResponse(String orgId, String displayName, Double rating, Double onTimeRate, Double defectRate,
                            boolean restricted, boolean listingsHidden, Instant updatedAt) {

        static StandingResponse of(SellerStanding s) {
            return new StandingResponse(s.orgId(), s.displayName(), s.rating(), s.onTimeRate(), s.defectRate(),
                    s.restricted(), s.listingsHidden(), s.updatedAt());
        }
    }

    @Schema(name = "SellerPerformanceRequest", description = "Null rates mean unknown (neutral defaults)")
    record PerformanceRequest(@DecimalMin("0") @DecimalMax("5") Double rating,
                              @DecimalMin("0") @DecimalMax("1") Double onTimeRate,
                              @DecimalMin("0") @DecimalMax("1") Double defectRate,
                              boolean restricted) {
    }
}
